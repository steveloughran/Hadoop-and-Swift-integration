package org.apache.hadoop.fs.swift.snative;

import org.apache.commons.httpclient.Header;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.swift.exceptions.SwiftException;
import org.apache.hadoop.fs.swift.http.SwiftProtocolConstants;
import org.apache.hadoop.fs.swift.http.SwiftRestClient;
import org.apache.hadoop.fs.swift.util.SwiftObjectPath;
import org.apache.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File system store implementation.
 * Makes REST requests, parses data from responses
 */
public class SwiftNativeFileSystemStore {
  private static final Pattern URI_PATTERN = Pattern.compile("\"\\S+?\"");
  private static final String PATTERN = "EEE, d MMM yyyy hh:mm:ss zzz";
  private URI uri;
  private SwiftRestClient swiftRestClient;

  public void initialize(URI uri, Configuration configuration) throws IOException {
    this.uri = uri;
    this.swiftRestClient = SwiftRestClient.getInstance(configuration);
  }

  public void uploadFile(Path path, InputStream inputStream, long length) throws IOException {
    swiftRestClient.upload(SwiftObjectPath.fromPath(uri, path), inputStream, length);
  }

  public void uploadFilePart(Path path, int partNumber, InputStream inputStream, long length) throws IOException {
    String stringPath = path.toUri().toString();
    if (stringPath.endsWith("/")) {
      stringPath = stringPath.concat(String.valueOf(partNumber));
    } else {
      stringPath = stringPath.concat("/").concat(String.valueOf(partNumber));
    }

    swiftRestClient.upload(new SwiftObjectPath(uri.getHost(), stringPath), inputStream, length);
  }

  public void createManifestForPartUpload(Path path) throws IOException {
    String pathString = SwiftObjectPath.fromPath(uri, path).toString();
    if (!pathString.endsWith("/")) {
      pathString = pathString.concat("/");
    }
    if (pathString.startsWith("/")) {
      pathString = pathString.substring(1);
    }

    swiftRestClient.upload(SwiftObjectPath.fromPath(uri, path), new ByteArrayInputStream(new byte[0]),
                           0, new Header(SwiftProtocolConstants.X_OBJECT_MANIFEST, pathString));
  }

  public FileStatus getObjectMetadata(Path path) throws IOException {
    final Header[] headers;
    headers = swiftRestClient.headRequest(SwiftObjectPath.fromPath(uri, path));
    if (headers == null || headers.length == 0) {
      return null;
    }

    boolean isDir = false;
    long length = 0;
    long lastModified = System.currentTimeMillis();
    for (Header header : headers) {
      if (header.getName().equals(SwiftProtocolConstants.X_CONTAINER_OBJECT_COUNT) ||
          header.getName().equals(SwiftProtocolConstants.X_CONTAINER_BYTES_USED)) {
        length = 0;
        isDir = true;
      }
      if (HttpHeaders.CONTENT_LENGTH.equals(header.getName())) {
        length = Long.parseLong(header.getValue());
      }
      if (HttpHeaders.LAST_MODIFIED.equals(header.getName())) {
        final SimpleDateFormat simpleDateFormat = new SimpleDateFormat(PATTERN);
        try {
          lastModified = simpleDateFormat.parse(header.getValue()).getTime();
        } catch (ParseException e) {
          throw new SwiftException("Failed to parse " + header.toString(), e);
        }
      }
    }

    final Path correctSwiftPath;
    try {
      correctSwiftPath = getCorrectSwiftPath(path);
    } catch (URISyntaxException e) {
      throw new SwiftException("Specified path " + path + " is incorrect", e);
    }
    return new FileStatus(length, isDir, 0, 0L, lastModified, correctSwiftPath);
  }

  public InputStream getObject(Path path) throws IOException {
    return swiftRestClient.getDataAsInputStream(SwiftObjectPath.fromPath(uri, path));
  }

  public InputStream getObject(Path path, long byteRangeStart, long length) throws IOException {
    return swiftRestClient.getDataAsInputStream(SwiftObjectPath.fromPath(uri, path), byteRangeStart, length);
  }

  public FileStatus[] listSubPaths(Path path) throws IOException {
    final Collection<FileStatus> fileStatuses;
    fileStatuses = listDirectory(SwiftObjectPath.fromPath(uri, path));

    return fileStatuses.toArray(new FileStatus[fileStatuses.size()]);
  }

  public void createDirectory(Path path) throws IOException {

    swiftRestClient.putRequest(SwiftObjectPath.fromPath(uri, path));
  }

  public List<URI> getObjectLocation(Path path) throws IOException {
    final byte[] objectLocation;
    objectLocation = swiftRestClient.getObjectLocation(SwiftObjectPath.fromPath(uri, path));
    return extractUris(new String(objectLocation));
  }

  /**
   * deletes object from Swift
   */
  public void deleteObject(Path path) throws IOException {

    swiftRestClient.delete(SwiftObjectPath.fromPath(uri, path));
  }

  /**
   * Checks if specified path exists
   *
   * @param path to check
   * @return true - path exists, false otherwise
   */
  public boolean objectExists(Path path) throws IOException {
    return !listDirectory(SwiftObjectPath.fromPath(uri, path)).isEmpty();
  }

  /**
   * Rename through copy-and-delete. this is clearly very inefficient, and
   * is a consequence of the classic Swift filesystem using the path as the hash
   * into the Distributed Hash Table, "the ring" of filenames.
   * 
   * Because of the nature of the operation, it is not atomic.
   * @param src source file/dir
   * @param dst destination
   * @return true if the entire rename was successful.
   * @throws IOException
   */
  public boolean renameDirectory(Path src, Path dst) throws IOException {
    final FileStatus srcMetadata = getObjectMetadata(src);
    final FileStatus dstMetadata = getObjectMetadata(dst);
    if (srcMetadata != null && !srcMetadata.isDir()) {
      if (dstMetadata != null && !dstMetadata.isDir()) {
        throw new SwiftException("file already exists: " + dst);
      }

      if (dstMetadata != null && dstMetadata.isDir()) {
        return swiftRestClient.copyObject(SwiftObjectPath.fromPath(uri, src),
                                          SwiftObjectPath.fromPath(uri, new Path(dst.getParent(), src.getName())));
      } else {
        return swiftRestClient.copyObject(SwiftObjectPath.fromPath(uri, src), SwiftObjectPath.fromPath(uri, dst));
      }
    }
    final List<FileStatus> fileStatuses = listDirectory(SwiftObjectPath.fromPath(uri, src.getParent()));
    final List<FileStatus> dstPath = listDirectory(SwiftObjectPath.fromPath(uri, dst.getParent()));

    if (dstPath.size() == 1 && !dstPath.get(0).isDir()) {
      throw new SwiftException("Cannot rename to: " + dst.toString());
    }

    boolean result = true;
    for (FileStatus fileStatus : fileStatuses) {
      if (!fileStatus.isDir()) {
        result &= swiftRestClient.copyObject(SwiftObjectPath.fromPath(uri, fileStatus.getPath()),
                                             SwiftObjectPath.fromPath(uri, dst));

        swiftRestClient.delete(SwiftObjectPath.fromPath(uri, fileStatus.getPath()));
      }
    }

    return result;
  }

  private List<FileStatus> listDirectory(SwiftObjectPath path) throws IOException {
    String uri = path.toUriPath();
    if (!uri.endsWith(Path.SEPARATOR)) {
      uri += Path.SEPARATOR;
    }

    final byte[] bytes;
    bytes = swiftRestClient.findObjectsByPrefix(path);
    if (bytes == null) {
      return Collections.emptyList();
    }

    final StringTokenizer tokenizer = new StringTokenizer(new String(bytes), "\n");
    final ArrayList<FileStatus> files = new ArrayList<FileStatus>();

    while (tokenizer.hasMoreTokens()) {
      String pathInSwift = tokenizer.nextToken();
      if (!pathInSwift.startsWith("/")) {
        pathInSwift = "/".concat(pathInSwift);
      }
      final FileStatus metadata = getObjectMetadata(new Path(pathInSwift));
      if (metadata != null) {
        files.add(metadata);
      }
    }

    return files;
  }

  private Path getCorrectSwiftPath(Path path) throws URISyntaxException {
    final URI fullUri = new URI(uri.getScheme(), uri.getAuthority(), path.toUri().getPath(), null, null);

    return new Path(fullUri);
  }

  /**
   * extracts URIs from json
   *
   * @return URIs
   */
  public static List<URI> extractUris(String json) {
    final Matcher matcher = URI_PATTERN.matcher(json);
    final List<URI> result = new ArrayList<URI>();
    while (matcher.find()) {
      final String s = matcher.group();
      final String uri = s.substring(1, s.length() - 1);
      result.add(URI.create(uri));
    }
    return result;
  }
}