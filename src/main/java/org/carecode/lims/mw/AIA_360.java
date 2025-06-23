// ChatGPT contribution: Production middleware for AIA_360 (Raw Chunk Based)
package org.carecode.lims.mw;

import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.*;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AIA_360 {

    public static final Logger logger = LogManager.getLogger("AIA_360Logger");
    public static MiddlewareSettings middlewareSettings;
    public static LISCommunicator limsUtils;
    public static boolean testingLis = false;

    private static final StringBuilder partialData = new StringBuilder();

    public static void main(String[] args) {
        logger.info("AIA_360 Middleware started at: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        loadSettings();

        if (middlewareSettings != null) {
            limsUtils = new LISCommunicator(logger, middlewareSettings);
            listenToAnalyzer();
        } else {
            logger.error("Failed to load settings.");
        }
    }

    public static void loadSettings() {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader("C:\\CCMW\\SysmaxXS500i\\settings\\AIA360\\config.json")) {
            middlewareSettings = gson.fromJson(reader, MiddlewareSettings.class);
            logger.info("Settings loaded from config.json");
        } catch (IOException e) {
            logger.error("Failed to load settings from config.json", e);
        }
    }

    public static void listenToAnalyzer() {
        String portName = middlewareSettings.getAnalyzerDetails().getAnalyzerIP();
        SerialPort analyzerPort = SerialPort.getCommPort(portName);

        analyzerPort.setBaudRate(9600);
        analyzerPort.setNumDataBits(8);
        analyzerPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        analyzerPort.setParity(SerialPort.NO_PARITY);

        logger.debug("Configured serial port: " + portName);
        logger.debug("BaudRate: " + analyzerPort.getBaudRate());
        logger.debug("DataBits: " + analyzerPort.getNumDataBits());
        logger.debug("StopBits: " + analyzerPort.getNumStopBits());
        logger.debug("Parity: " + analyzerPort.getParity());

        if (!analyzerPort.openPort()) {
            logger.error("❌ Failed to open serial port: " + portName);
            return;
        }

        logger.info("✅ Listening to analyzer on port: " + analyzerPort.getSystemPortName());
        logger.info("Port open status: " + analyzerPort.isOpen());

        new Thread(() -> {
            byte[] buffer = new byte[1024];

            while (true) {
                try {
                    int available = analyzerPort.bytesAvailable();
                    if (available == 0) {
                        Thread.sleep(100);
                        continue;
                    }

                    int numRead = analyzerPort.readBytes(buffer, buffer.length);
                    logger.debug("Read bytes: " + numRead);

                    if (numRead > 0) {
                        String received = new String(buffer, 0, numRead, StandardCharsets.UTF_8);
                        logger.debug("Raw received chunk: [" + received + "]");
                        partialData.append(received);

                        if (partialData.toString().contains("Date=")) {
                            String fullMessage = partialData.toString().replaceAll("[^\\u0020-\\u007E]", "").trim();
                            logger.info("🟢 Complete message received:\n" + fullMessage);
                            processFormattedResult(fullMessage);
                            partialData.setLength(0);
                        }
                    } else {
                        logger.warn("readBytes() returned 0 despite bytesAvailable > 0");
                    }

                } catch (Exception e) {
                    logger.error("⚠️ Error while reading from serial port", e);
                }
            }
        }).start();
    }

    private static void processFormattedResult(String fullText) {
        try {
            logger.debug("🔍 Raw message block:\n" + fullText);

            String[] tokens = fullText.split(",");
            String sampleNo = null;
            String analyte = null;
            String result = null;
            String rate = null;
            String unit = null;
            String flag = null;
            String date = null;

            for (int i = 0; i < tokens.length - 1; i++) {
                String key = tokens[i].trim();
                String value = tokens[i + 1].trim();

                switch (key) {
                    case "SampleID=":
                        sampleNo = value;
                        logger.info("📌 Sample ID     : " + sampleNo);
                        break;
                    case "Analyte=":
                        analyte = value.replaceAll("[^a-zA-Z0-9]", "");
                        logger.info("🧪 Analyte Code  : " + analyte);
                        break;
                    case "Conc=":
                        result = value;
                        logger.info("📈 Result Value  : " + result);
                        break;
                    case "Rate=":
                        rate = value;
                        logger.info("⏱️  Rate Value    : " + rate);
                        break;
                    case "Unit=":
                        unit = value;
                        logger.info("📏 Unit          : " + unit);
                        break;
                    case "Flag=":
                        flag = value;
                        logger.info("🚩 Flag          : " + flag);
                        break;
                    case "Date=":
                        date = value;
                        logger.info("🗓️  Timestamp     : " + date);
                        break;
                }
            }

            if (sampleNo == null || analyte == null || result == null) {
                logger.warn("❌ Required fields missing: SampleID=" + sampleNo + ", Analyte=" + analyte + ", Result=" + result);
                return;
            }

            sendSingleResult(null, sampleNo, analyte, result, null, null);
            if (rate != null) {
                sendSingleResult(null, sampleNo, analyte + "_RATE", rate, null, null);
            }

            logger.info("✅ Result(s) sent to LIMS for Sample=" + sampleNo);
        } catch (Exception e) {
            logger.error("❌ Error processing formatted result", e);
        }
    }

    private static void sendSingleResult(String patientId, String sampleNo, String testCode, String resultValue, Double minValue, Double maxValue) {
        try {
            if (minValue == null) {
                minValue = 0.0;
            }
            if (maxValue == null) {
                maxValue = 9999.0;
            }

            DataBundle dataBundle = new DataBundle();
            dataBundle.setMiddlewareSettings(middlewareSettings);

            PatientRecord patientRecord = new PatientRecord(
                    0, sampleNo, null, "Unknown", null, null, null, null, null, null, null
            );
            dataBundle.setPatientRecord(patientRecord);

            ResultsRecord resultsRecord = new ResultsRecord(
                    0, testCode, resultValue, minValue, maxValue, "",
                    "Serum", "", null, null, sampleNo
            );
            dataBundle.addResultsRecord(resultsRecord);

            limsUtils.pushResults(dataBundle);
            logger.info("📤 Result pushed to LIMS: " + testCode + " = " + resultValue);
        } catch (Exception e) {
            logger.error("❌ Error sending result for test: " + testCode, e);
        }
    }

}
