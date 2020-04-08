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

package io.cdap.plugin.cloud.vision.action;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.Credentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AsyncBatchAnnotateImagesRequest;
import com.google.cloud.vision.v1.CropHintsParams;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.GcsDestination;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import com.google.cloud.vision.v1.ImageContext;
import com.google.cloud.vision.v1.ImageSource;
import com.google.cloud.vision.v1.OutputConfig;
import com.google.cloud.vision.v1.WebDetectionParams;
import com.google.common.base.Strings;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.action.Action;
import io.cdap.cdap.etl.api.action.ActionContext;
import io.cdap.plugin.cloud.vision.CredentialsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import static io.cdap.plugin.cloud.vision.action.ActionConstants.MAX_NUMBER_OF_IMAGES_PER_BATCH;

/**
 * Action that runs offline image extractor.
 */
@Plugin(type = Action.PLUGIN_TYPE)
@Name(OfflineImageExtractorAction.PLUGIN_NAME)
@Description("Action that runs offline image extractor.")
public class OfflineImageExtractorAction extends Action {
  public static final String PLUGIN_NAME = "OfflineImageExtractor";
  private final OfflineImageExtractorActionConfig config;
  private static final Logger LOG = LoggerFactory.getLogger(OfflineImageExtractorAction.class);

  public OfflineImageExtractorAction(OfflineImageExtractorActionConfig config) {
    if (config.getSourcePath() != null) {
      config.setSourcePath(config.getSourcePath().trim()); // Remove whitespace
    }
    if (config.getDestinationPath() != null) {
      config.setDestinationPath(config.getDestinationPath().trim()); // Remove whitespace
    }
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    FailureCollector collector = pipelineConfigurer.getStageConfigurer().getFailureCollector();
    config.validate(collector);
    collector.getOrThrowException();
  }

  @Override
  public void run(ActionContext actionContext) throws Exception {
    FailureCollector collector = actionContext.getFailureCollector();
    config.validate(collector);
    collector.getOrThrowException();

    Credentials credentials = CredentialsHelper.getCredentials(config.getServiceFilePath());

    // Destination in GCS where the results will be stored
    String destinationPath = config.getDestinationPath();
    // Add a '/' at the end if it's not already there
    if (!destinationPath.endsWith("/")) {
      destinationPath += "/";
    }
    GcsDestination gcsDestination = GcsDestination.newBuilder()
        .setUri(destinationPath)
        .build();

    OutputConfig outputConfig = OutputConfig.newBuilder()
        .setGcsDestination(gcsDestination)
        .setBatchSize(config.getBatchSizeValue())
        .build();

    ImageAnnotatorSettings imageAnnotatorSettings = ImageAnnotatorSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
        .build();

    // Get all the blobs in the source path
    List<Blob> blobs = GcsBucketHelper.getAllFilesInPath(config.getSourcePath(), credentials);
    if (blobs.isEmpty()) {
      LOG.warn("Nothing found to process in path: " + config.getSourcePath());
      return;
    }

    // Prepare the list of requests
    List<AnnotateImageRequest> imageRequests = new ArrayList<>(blobs.size());

    // Feature we are going to ask for
    Feature feature = Feature.newBuilder()
        .setType(config.getImageFeature().getFeatureType())
        .build();

    try (ImageAnnotatorClient imageAnnotatorClient = ImageAnnotatorClient.create(imageAnnotatorSettings)) {
      // Create batches of images to send for processing
      // We need to do this because there is a limit on the vision API that will raise an error if there are more
      // than MAX_NUMBER_OF_IMAGES_PER_BATCH in a single batch
      for (int batchId = 0;
           batchId < (1 + blobs.size() / MAX_NUMBER_OF_IMAGES_PER_BATCH);
           batchId++) {
        for (int index = batchId * MAX_NUMBER_OF_IMAGES_PER_BATCH;
             (index < (batchId + 1) * MAX_NUMBER_OF_IMAGES_PER_BATCH) && (index < blobs.size());
             index++) {
          Blob blob = blobs.get(index);
          // Rebuild the full path of the blob
          String fullBlobPath = "gs://" + blob.getBucket() + "/" + blob.getName();

          ImageSource imageSource = ImageSource.newBuilder()
              .setImageUri(fullBlobPath)
              .build();

          Image image = Image.newBuilder()
              .setSource(imageSource)
              .build();

          AnnotateImageRequest.Builder builder =
              AnnotateImageRequest.newBuilder()
                  .setImage(image)
                  .addFeatures(feature);

          ImageContext imageContext = getImageContext();
          if (imageContext != null) {
            builder.setImageContext(imageContext);
          }

          AnnotateImageRequest annotateImageRequest = builder.build();
          imageRequests.add(annotateImageRequest);
        }

        // Send the requests
        AsyncBatchAnnotateImagesRequest asyncRequest = AsyncBatchAnnotateImagesRequest.newBuilder()
            .addAllRequests(imageRequests)
            .setOutputConfig(outputConfig)
            .build();

        // Wait for the future to complete
        imageAnnotatorClient.asyncBatchAnnotateImagesAsync(asyncRequest)
            .getInitialFuture()
            .get();
      }
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Nullable
  protected ImageContext getImageContext() {
    switch (config.getImageFeature()) {
      case TEXT:
        return Strings.isNullOrEmpty(config.getLanguageHints()) ? null
            : ImageContext.newBuilder().addAllLanguageHints(config.getLanguages()).build();
      case CROP_HINTS:
        CropHintsParams cropHintsParams = CropHintsParams.newBuilder()
            .addAllAspectRatios(config.getAspectRatiosList())
            .build();
        return Strings.isNullOrEmpty(config.getAspectRatios()) ? null
            : ImageContext.newBuilder().setCropHintsParams(cropHintsParams).build();
      case WEB_DETECTION:
        WebDetectionParams webDetectionParams = WebDetectionParams.newBuilder()
            .setIncludeGeoResults(config.getIncludeGeoResults())
            .build();
        return ImageContext.newBuilder().setWebDetectionParams(webDetectionParams).build();
      default:
        return null;
    }
  }
}
