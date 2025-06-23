// ChatGPT contribution: Production middleware for AIA_360 (ASTM-based)
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
import java.util.ArrayList;
import java.util.List;

public class AIA_360 {

    public static final Logger logger = LogManager.getLogger("AIA_360Logger");
    public static MiddlewareSettings middlewareSettings;
    public static LISCommunicator limsUtils;
    public static boolean testingLis = false;

    private static final List<String> aia360BlockBuffer = new ArrayList<>();

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
        try (FileReader reader = new FileReader("config.json")) {
            middlewareSettings = gson.fromJson(reader, MiddlewareSettings.class);
            logger.info("Settings loaded from config.json");
        } catch (IOException e) {
            logger.error("Failed to load settings from config.json", e);
        }
    }

    public static void listenToAnalyzer() {
        SerialPort analyzerPort = SerialPort.getCommPort(middlewareSettings.getAnalyzerDetails().getAnalyzerIP());

        analyzerPort.setBaudRate(19200);
        analyzerPort.setNumDataBits(8);
        analyzerPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        analyzerPort.setParity(SerialPort.NO_PARITY);

        if (!analyzerPort.openPort()) {
            logger.error("Failed to open serial port: " + middlewareSettings.getAnalyzerDetails().getAnalyzerIP());
            return;
        }

        logger.info("Listening to analyzer on port: " + analyzerPort.getSystemPortName());

        new Thread(() -> {
            byte[] buffer = new byte[1024];
            StringBuilder partialData = new StringBuilder();

            while (true) {
                try {
                    if (analyzerPort.bytesAvailable() > 0) {
                        int numRead = analyzerPort.readBytes(buffer, buffer.length);
                        String received = new String(buffer, 0, numRead, StandardCharsets.UTF_8);
                        partialData.append(received);

                        String[] lines = partialData.toString().split("\r?\n");
                        for (int i = 0; i < lines.length - 1; i++) {
                            processLine(lines[i]);
                        }
                        partialData = new StringBuilder(lines[lines.length - 1]);
                    }
                    Thread.sleep(100);
                } catch (Exception e) {
                    logger.error("Error while reading from serial port", e);
                }
            }
        }).start();
    }

    private static void processLine(String line) {
        line = line.replaceAll("[^\\u0020-\\u007E]", "").trim();
        if (line.isEmpty()) {
            return;
        }
        logger.info("Received Line: " + line);

        if (line.startsWith("H|")) {
            aia360BlockBuffer.clear();
        }
        aia360BlockBuffer.add(line);

        if (line.startsWith("L|")) {
            processAIA360MessageBlock(new ArrayList<>(aia360BlockBuffer));
            aia360BlockBuffer.clear();
        }
    }

    private static void processAIA360MessageBlock(List<String> lines) {
        String sampleNo = null, analyteCode = null, resultValue = null, rateValue = null;

        for (String line : lines) {
            line = line.replaceAll("[^\\u0020-\\u007E]", "").trim();

            if (line.startsWith("O|")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 5) {
                    sampleNo = parts[2];
                    String[] testIdParts = parts[4].split("\\^");
                    if (testIdParts.length >= 4) {
                        analyteCode = testIdParts[3];
                    }
                }
            } else if (line.startsWith("R|1|")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 4) {
                    resultValue = parts[3];
                }
            } else if (line.startsWith("R|2|")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 4) {
                    rateValue = parts[3];
                }
            }
        }

        if (sampleNo != null && analyteCode != null && resultValue != null) {
            sendSingleResult(null, sampleNo, analyteCode, resultValue, null, null);
            if (rateValue != null) {
                sendSingleResult(null, sampleNo, analyteCode + "_RATE", rateValue, null, null);
            }
        } else {
            logger.warn("Incomplete message block: Sample=" + sampleNo + ", Analyte=" + analyteCode);
        }
    }

    private static void sendSingleResult(String patientId, String sampleNo, String testCode, String resultValue, Double minValue, Double maxValue) {
        try {
            DataBundle dataBundle = new DataBundle();
            dataBundle.setMiddlewareSettings(middlewareSettings);

            PatientRecord patientRecord = new PatientRecord(0, patientId, null, "Unknown", null, null, null, null, null, null, null);
            dataBundle.setPatientRecord(patientRecord);

            ResultsRecord resultsRecord = new ResultsRecord(0, testCode, resultValue, minValue, maxValue, "", "Serum", "", null, null, patientId);
            dataBundle.addResultsRecord(resultsRecord);

            limsUtils.pushResults(dataBundle);
            logger.info("Result pushed: " + testCode + " for sample: " + sampleNo);
        } catch (Exception e) {
            logger.error("Error sending result for: " + testCode, e);
        }
    }
}
