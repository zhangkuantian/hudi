/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.io;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.engine.TaskContextSupplier;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.marker.WriteMarkers;
import org.apache.hudi.table.marker.WriteMarkersFactory;

import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * A {@link HoodieMergeHandle} that supports MERGE write incrementally(small data buffers).
 *
 * <P>This handle is needed from the second mini-batch write for COW data bucket
 * when the data bucket is written using multiple mini-batches.
 *
 * <p>For the incremental data buffer, it initializes and sets up the next file path to write,
 * then closes the file and rename to the old file name,
 * behaves like the new data buffer are appended to the old file.
 */
public class FlinkMergeAndReplaceHandle<T extends HoodieRecordPayload, I, K, O>
    extends HoodieMergeHandle<T, I, K, O>
    implements MiniBatchHandle {

  private static final Logger LOG = LogManager.getLogger(FlinkMergeAndReplaceHandle.class);

  private boolean isClosed = false;

  /**
   * Flag saying whether we should replace the old file with new.
   */
  private boolean shouldReplace = true;

  public FlinkMergeAndReplaceHandle(HoodieWriteConfig config, String instantTime, HoodieTable<T, I, K, O> hoodieTable,
                                    Iterator<HoodieRecord<T>> recordItr, String partitionPath, String fileId,
                                    TaskContextSupplier taskContextSupplier, Path basePath) {
    super(config, instantTime, hoodieTable, recordItr, partitionPath, fileId, taskContextSupplier,
        new HoodieBaseFile(basePath.toString()), Option.empty());
    // delete invalid data files generated by task retry.
    if (getAttemptId() > 0) {
      deleteInvalidDataFile(getAttemptId() - 1);
    }
  }

  /**
   * The flink checkpoints start in sequence and asynchronously, when one write task finish the checkpoint(A)
   * (thus the fs view got the written data files some of which may be invalid),
   * it goes on with the next round checkpoint(B) write immediately,
   * if it tries to reuse the last small data bucket(small file) of an invalid data file,
   * finally, when the coordinator receives the checkpoint success event of checkpoint(A),
   * the invalid data file would be cleaned,
   * and this merger got a FileNotFoundException when it close the write file handle.
   *
   * <p> To solve, deletes the invalid data file eagerly
   * so that the invalid file small bucket would never be reused.
   *
   * @param lastAttemptId The last attempt ID
   */
  private void deleteInvalidDataFile(long lastAttemptId) {
    final String lastWriteToken = FSUtils.makeWriteToken(getPartitionId(), getStageId(), lastAttemptId);
    final String lastDataFileName = FSUtils.makeDataFileName(instantTime,
        lastWriteToken, this.fileId, hoodieTable.getBaseFileExtension());
    final Path path = makeNewFilePath(partitionPath, lastDataFileName);
    try {
      if (fs.exists(path)) {
        LOG.info("Deleting invalid MERGE and REPLACE base file due to task retry: " + lastDataFileName);
        fs.delete(path, false);
      }
    } catch (IOException e) {
      throw new HoodieException("Error while deleting the MERGE and REPLACE base file due to task retry: " + lastDataFileName, e);
    }
  }

  @Override
  protected void createMarkerFile(String partitionPath, String dataFileName) {
    WriteMarkers writeMarkers = WriteMarkersFactory.get(config.getMarkersType(), hoodieTable, instantTime);
    writeMarkers.createIfNotExists(partitionPath, dataFileName, getIOType());
  }

  @Override
  protected void makeOldAndNewFilePaths(String partitionPath, String oldFileName, String newFileName) {
    // old and new file name expects to be the same.
    if (!FSUtils.getCommitTime(oldFileName).equals(instantTime)) {
      LOG.warn("MERGE and REPLACE handle expect the same name for old and new files,\n"
          + "while got new file: " + newFileName + " with old file: " + oldFileName + ",\n"
          + "this rarely happens when the checkpoint success event was not received yet\n"
          + "but the write task flush with new instant time, which does not break the UPSERT semantics");
      shouldReplace = false;
    }
    super.makeOldAndNewFilePaths(partitionPath, oldFileName, newFileName);
    try {
      int rollNumber = 0;
      while (fs.exists(newFilePath)) {
        Path oldPath = newFilePath;
        newFileName = newFileNameWithRollover(rollNumber++);
        newFilePath = makeNewFilePath(partitionPath, newFileName);
        LOG.warn("Duplicate write for MERGE and REPLACE handle with path: " + oldPath + ", rolls over to new path: " + newFilePath);
      }
    } catch (IOException e) {
      throw new HoodieException("Checking existing path for merge and replace handle error: " + newFilePath, e);
    }
  }

  /**
   * Use the writeToken + "-" + rollNumber as the new writeToken of a mini-batch write.
   */
  protected String newFileNameWithRollover(int rollNumber) {
    // make the intermediate file as hidden
    final String fileID = "." + this.fileId;
    return FSUtils.makeDataFileName(instantTime, writeToken + "-" + rollNumber,
        fileID, hoodieTable.getBaseFileExtension());
  }

  @Override
  protected void setWriteStatusPath() {
    // should still report the old file path.
    writeStatus.getStat().setPath(new Path(config.getBasePath()), oldFilePath);
  }

  boolean needsUpdateLocation() {
    // No need to update location for Flink hoodie records because all the records are pre-tagged
    // with the desired locations.
    return false;
  }

  public void finalizeWrite() {
    // Behaves like the normal merge handle if the write instant time changes.
    if (!shouldReplace) {
      return;
    }
    // The file visibility should be kept by the configured ConsistencyGuard instance.
    try {
      fs.delete(oldFilePath, false);
    } catch (IOException e) {
      throw new HoodieIOException("Error while cleaning the old base file: " + oldFilePath, e);
    }
    try {
      fs.rename(newFilePath, oldFilePath);
    } catch (IOException e) {
      throw new HoodieIOException("Error while renaming the temporary roll file: "
          + newFilePath + " to old base file name: " + oldFilePath, e);
    }
  }

  @Override
  public List<WriteStatus> close() {
    try {
      List<WriteStatus> writeStatuses = super.close();
      finalizeWrite();
      return writeStatuses;
    } finally {
      this.isClosed = true;
    }
  }

  @Override
  public void closeGracefully() {
    if (isClosed) {
      return;
    }
    try {
      close();
    } catch (Throwable throwable) {
      LOG.warn("Error while trying to dispose the MERGE handle", throwable);
      try {
        fs.delete(newFilePath, false);
        LOG.info("Deleting the intermediate MERGE and REPLACE data file: " + newFilePath + " success!");
      } catch (IOException e) {
        // logging a warning and ignore the exception.
        LOG.warn("Deleting the intermediate MERGE and REPLACE data file: " + newFilePath + " failed", e);
      }
    }
  }

  @Override
  public Path getWritePath() {
    return oldFilePath;
  }
}
