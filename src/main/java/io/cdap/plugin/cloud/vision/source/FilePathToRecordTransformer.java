/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.cloud.vision.source;

import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.plugin.cloud.vision.FilePathSourceConfig;

/**
 * Transforms {@link String} to {@link StructuredRecord}.
 */
public class FilePathToRecordTransformer {

  private final FilePathSourceConfig config;
  private final Schema schema;

  public FilePathToRecordTransformer(FilePathSourceConfig config, Schema schema) {
    this.config = config;
    this.schema = schema;
  }

  /**
   * Transforms given {@link String} to {@link StructuredRecord}.
   *
   * @param filePath string file path to be transformed.
   * @return {@link StructuredRecord} that corresponds to the given {@link String}.
   */
  public StructuredRecord transform(String filePath) {
    // TODO implement
    return null;
  }
}
