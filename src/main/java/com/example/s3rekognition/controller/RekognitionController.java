package com.example.s3rekognition.controller;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.example.s3rekognition.ModerationClassificationResponse;
import com.example.s3rekognition.ModerationResponse;
import com.example.s3rekognition.PPEClassificationResponse;
import com.example.s3rekognition.PPEResponse;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;


@RestController
public class RekognitionController implements ApplicationListener<ApplicationReadyEvent> {

    private final AmazonS3 s3Client;
    private final AmazonRekognition rekognitionClient;
    private final MeterRegistry meterRegistry;


    private static final Map<String, ModerationClassificationResponse> dangers = new HashMap<>();


    private static final Logger logger = Logger.getLogger(RekognitionController.class.getName());

    @Autowired
    public RekognitionController(MeterRegistry meterRegistry) {
        this.s3Client = AmazonS3ClientBuilder.standard().withRegion(Regions.EU_WEST_1).build();
        this.rekognitionClient = AmazonRekognitionClientBuilder.standard().withRegion(Regions.EU_WEST_1).build();
        this.meterRegistry = meterRegistry;
    }


    @GetMapping(value = "/get-danger", consumes = "*/*", produces = "application/json")
    public ResponseEntity<ModerationResponse> getAllDangers() {

        List<ModerationClassificationResponse> classificationResponses = new ArrayList<>(dangers.values());

        ModerationResponse response = new ModerationResponse("", classificationResponses);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/handle-danger", consumes = "*/*", produces = "application/json")
    public ResponseEntity<ModerationResponse> handleDanger(@RequestParam String imageKey) {

        dangers.remove(imageKey);
        List<ModerationClassificationResponse> classificationResponses = new ArrayList<>(dangers.values());

        ModerationResponse response = new ModerationResponse("", classificationResponses);
        return ResponseEntity.ok(response);
    }


    @GetMapping(value = "/scan-moderation", consumes = "*/*", produces = "application/json")
    public ResponseEntity<ModerationResponse> scanForStuff(@RequestParam String bucketName) {
        ListObjectsV2Result imageList = s3Client.listObjectsV2(bucketName);

        // This will hold all of our classifications
        List<ModerationClassificationResponse> classificationResponses = new ArrayList<>();

        // This is all the images in the bucket
        List<S3ObjectSummary> images = imageList.getObjectSummaries();
//

//        LongTaskTimer.Sample currentTaskId = longTaskTimer.start();
        LongTaskTimer longTaskTimer = LongTaskTimer.builder("scan-danger").register(meterRegistry);
        LongTaskTimer.Sample currentTaskId = longTaskTimer.start();

        for (S3ObjectSummary image : images) {
            logger.info("scanning " + image.getKey());
            DetectModerationLabelsRequest request = new DetectModerationLabelsRequest()
                    .withImage(
                            new Image()
                                    .withS3Object(new S3Object()
                                            .withBucket(bucketName)
                                            .withName(image.getKey()))).withMinConfidence(80.0f);

            DetectModerationLabelsResult result = rekognitionClient.detectModerationLabels(request);


            boolean violation = isViolation(result);

            logger.info("scanning " + image.getKey() + ", violation result " + violation);

            ModerationClassificationResponse classification = new ModerationClassificationResponse(image.getKey(), violation, result.getModerationLabels());
            classificationResponses.add(classification);
            if (violation) {
                dangers.put(image.getKey(), classification);
            }
        }
        currentTaskId.stop();

        ModerationResponse response = new ModerationResponse(bucketName, classificationResponses);
        return ResponseEntity.ok(response);
    }

    /**
     * This endpoint takes an S3 bucket name in as an argument, scans all the
     * Files in the bucket for Protective Gear Violations.
     * <p>
     *
     * @param bucketName
     * @return
     */
    @GetMapping(value = "/scan-ppe", consumes = "*/*", produces = "application/json")
    @ResponseBody
    @Timed
    public ResponseEntity<PPEResponse> scanForPPE(@RequestParam String bucketName) {
        // List all objects in the S3 bucket
        ListObjectsV2Result imageList = s3Client.listObjectsV2(bucketName);

        // This will hold all of our classifications
        List<PPEClassificationResponse> classificationResponses = new ArrayList<>();

        // This is all the images in the bucket
        List<S3ObjectSummary> images = imageList.getObjectSummaries();

//        Timer timer = meterRegistry.timer("test-timer");
        // Iterate over each object and scan for PPE
        LongTaskTimer longTaskTimer = LongTaskTimer.builder("scan-ppe").register(meterRegistry);
        LongTaskTimer.Sample currentTaskId = longTaskTimer.start();
        for (S3ObjectSummary image : images) {
            logger.info("scanning " + image.getKey());

            // This is where the magic happens, use AWS rekognition to detect PPE
            DetectProtectiveEquipmentRequest request = new DetectProtectiveEquipmentRequest()
                    .withImage(new Image()
                            .withS3Object(new S3Object()
                                    .withBucket(bucketName)
                                    .withName(image.getKey())))
                    .withSummarizationAttributes(new ProtectiveEquipmentSummarizationAttributes()
                            .withMinConfidence(80f)
                            .withRequiredEquipmentTypes("FACE_COVER", "HAND_COVER"));

            DetectProtectiveEquipmentResult result = rekognitionClient.detectProtectiveEquipment(request);

            // If any person on an image lacks PPE on the face, it's a violation of regulations
            boolean violation = !result.getSummary().getPersonsWithoutRequiredEquipment().isEmpty();
            if (!violation) {
                meterRegistry.counter("scan", "PPE", "violations").increment();
            } else {
                meterRegistry.counter("scan", "PPE", "none-violations").increment();
            }

            logger.info("scanning " + image.getKey() + ", violation result " + violation);
            // Categorize the current image as a violation or not.
            int personCount = result.getPersons().size();
            PPEClassificationResponse classification = new PPEClassificationResponse(image.getKey(), personCount, violation);
            classificationResponses.add(classification);
        }
        currentTaskId.stop();
        PPEResponse ppeResponse = new PPEResponse(bucketName, classificationResponses);
        return ResponseEntity.ok(ppeResponse);
    }

    private static boolean isViolation(DetectModerationLabelsResult result) {
        List<String> strings = new ArrayList<>();
        strings.add("violence");
        return result.getModerationLabels()
                .stream()
                .anyMatch(l -> strings.contains(l.getName().toLowerCase()));
    }


    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
//        meterRegistry.gauge("danger-violation",dangers.size());
        Gauge.builder("danger-violation", dangers, Map::size).tags("Weapon","Found").register(meterRegistry);
//        Gauge.builder("danger-violation",() -> kake).register(meterRegistry);
//        longTaskTimer = LongTaskTimer.builder("danger-timer").register(meterRegistry);
    }
}
