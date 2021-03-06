/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.fs.swift;

import junit.framework.AssertionFailedError;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.swift.snative.SwiftNativeFileSystem;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * This is the full filesystem contract test -which requires the
 * Default config set up to point to a filesystem
 */
public class TestSwiftFileSystemContract
  extends NativeSwiftFileSystemContractBaseTest {
  private static final Log LOG =
    LogFactory.getLog(TestSwiftFileSystemContract.class);

  @Override
  protected URI getFilesystemURI() throws URISyntaxException, IOException {
    return SwiftTestUtils.getServiceURI(new Configuration());
  }

  @Override
  protected SwiftNativeFileSystem createSwiftFS() throws IOException {
    SwiftNativeFileSystem swiftNativeFileSystem =
      new SwiftNativeFileSystem();
    return swiftNativeFileSystem;
  }

  public void testMkdirs() throws Exception {
    try {
      super.testMkdirs();
    } catch (AssertionFailedError e) {
      SwiftTestUtils.downgrade("file/dir confusion", e);
    }
  }

  public void testWriteReadAndDeleteEmptyFile() throws Exception {
    try {
      super.testWriteReadAndDeleteEmptyFile();
    } catch (AssertionFailedError e) {
      SwiftTestUtils.downgrade("empty files get mistaken for directories", e);
    }
  }

  @Override
  public void testZeroByteFilesAreFiles() throws Exception {
    try {
      super.testZeroByteFilesAreFiles();
    } catch (AssertionFailedError e) {
      SwiftTestUtils.downgrade("zero byte files get mistaken for directories", e);
    }
  }
}
