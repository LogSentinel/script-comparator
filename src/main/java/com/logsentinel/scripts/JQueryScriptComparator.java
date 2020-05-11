package com.logsentinel.scripts;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLHandshakeException;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class JQueryScriptComparator {

    private static final String MIN = ".min";
    private static final String JQUREY_PATH = "wp-includes/js/jquery/jquery.js";
    private static final String JQUERY_MIN_VERSION_PREFIX = "jQuery v";
    private static final String JQUREY_VERSION_PREFIX = "jQuery JavaScript Library v";
    
    private static final String GITHUB_ROOT = "https://raw.githubusercontent.com/jquery/jquery/";
    private static final String GITHUB_WP_JQUERY_VERSION_URL = "https://raw.githubusercontent.com/WordPress/WordPress/5.3/wp-includes/js/jquery/jquery.js";
    
    public static void main(String[] args) throws Exception {
        System.setProperty("http.agent", "scriptinel.com");
        System.setProperty("sun.net.client.defaultConnectTimeout", "7000");
        System.setProperty("sun.net.client.defaultReadTimeout", "15000");
        
        String outPath = args.length > 1 ? args[1] : System.getProperty("java.io.tmpdir") + "/jquery.csv";
        
        List<String> urls = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.withSkipHeaderRecord().parse(
                new InputStreamReader(new FileInputStream(new File(args[0])), StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                String url = record.get(0);
                urls.add(url);
            }
        }
        System.out.println("Working with " + urls.size() + " URLs");
        
        BlockingQueue<List<String>> queue = new LinkedBlockingQueue<>();
        ExecutorService queueRunner = Executors.newSingleThreadExecutor();
        queueRunner.execute(() -> {
           try {
               List<String> line;
               try (CSVPrinter out = new CSVPrinter(new FileWriter(outPath, true), CSVFormat.DEFAULT)) {
                   while ((line = queue.poll(60, TimeUnit.SECONDS)) != null) {
                       try {
                           out.printRecord(line);
                           out.flush();
                       } catch (Exception ex) {
                           ex.printStackTrace();
                       }
                   }
               }
           } catch (Exception ex) {
               ex.printStackTrace();
           }
        });
        
        Map<String, String> hashes = new HashMap<>();
        urls.parallelStream().forEach(url -> {
            try {
                url = url + JQUREY_PATH;
                for (String suffix : Arrays.asList("", MIN)) {
                    String scriptUrl = url.replace(".js", suffix + ".js");
                    String script = IOUtils.toString(new URL(scriptUrl), StandardCharsets.UTF_8);
                    if (StringUtils.isBlank(script)) {
                        continue;
                    }
                    
                    String prefix = suffix.equals(MIN) ? JQUERY_MIN_VERSION_PREFIX : JQUREY_VERSION_PREFIX;
                    int versionIdx = script.indexOf(prefix);
                    if (versionIdx < 0) {
                        // in many cases jquery.js is actually minified
                        if (suffix.isBlank()) {
                            versionIdx = script.indexOf(JQUERY_MIN_VERSION_PREFIX);
                            if (versionIdx < 0) {
                                continue;
                            } else {
                                suffix = MIN;
                                prefix = JQUERY_MIN_VERSION_PREFIX;
                            }
                        } else {
                            continue;
                        }
                    }
                    
                    int endIdx = suffix.equals(MIN) ? script.indexOf('|') : script.indexOf('\n', versionIdx);
                    String version = script.substring(versionIdx + prefix.length(), endIdx).trim();
                    boolean wpVersion = script.contains("| WordPress");
                    String hash = hashes.get(version + (wpVersion ? "-wp" : ""));
                    if (hash == null) {
                        String gitHubScript;
                        if (wpVersion) {
                            gitHubScript = normalize(IOUtils.toString(new URL(GITHUB_WP_JQUERY_VERSION_URL), StandardCharsets.UTF_8));
                        } else {
                            gitHubScript = normalize(IOUtils.toString(new URL(GITHUB_ROOT + version + "/dist/jquery" + suffix + ".js"), StandardCharsets.UTF_8));
                        }
                        hash = DigestUtils.sha1Hex(gitHubScript); 
                        hashes.put(version + (wpVersion ? "-wp" : ""), hash);
                    }
                    
                    script = normalize(script);
                    if (!DigestUtils.sha1Hex(script).equals(hash)) {
                        queue.put(Arrays.asList(scriptUrl, "mismatch"));
                    } else {
                        queue.put(Arrays.asList(scriptUrl, "ok"));
                    }
                }
                Thread.sleep(100);
            } catch (UnknownHostException | ConnectException | SSLHandshakeException | FileNotFoundException | SocketTimeoutException ex) {
                // ignore
            } catch (Exception ex) {
                if (ex.getMessage().startsWith("Server returned HTTP response code")) {
                    // ignore
                } else {
                    ex.printStackTrace();
                }
            }
        });
    }

    private static String normalize(String script) {
        return script.replace("//# sourceMappingURL=jquery.min.map", "")
                .replace("//# sourceMappingURL=jquery.map", "")
                .replace("jQuery.noConflict();", "")
                .replaceAll("\\s", "")
                .replace("//", "")
                .trim();

    }
}
