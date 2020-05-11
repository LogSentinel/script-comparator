package com.logsentinel.scripts;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
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

public class WooCommerceScriptComparator {

    private static final int LENGTH_DIFF_THRESHOLD = 10;

    private static String META_VERSION_PREFIX = "<meta name=\"generator\" content=\"WooCommerce ";

    private static String WOOCOMMERCE_PREFIX = "wp-content/plugins/woocommerce";
    
    private static String GITHUB_ROOT = "https://raw.githubusercontent.com/woocommerce/woocommerce/";
    
    private static Map<String, String> hashes = new HashMap<>();
    private static Map<String, Integer> lengths = new HashMap<>();
    
    public static void main(String[] args) throws Exception {
        
        System.setProperty("http.agent", "scriptinel.com");
        System.setProperty("sun.net.client.defaultConnectTimeout", "7000");
        System.setProperty("sun.net.client.defaultReadTimeout", "15000");

        String outPath = args.length > 1 ? args[1] : System.getProperty("java.io.tmpdir") + "/wocommerce.csv";
        
        List<String> scripts = Arrays.asList("/assets/js/jquery-payment/jquery.payment.js", "/assets/js/frontend/woocommerce.js", 
                "/assets/js/frontend/checkout.js", "/assets/js/frontend/cart.js",
                "/assets/js/frontend/credit-card-form.js");

        AtomicInteger problematicHosts = new AtomicInteger();
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
        
        urls.parallelStream().forEach(url -> {
            try {
                String home = IOUtils.toString(new URL(url), StandardCharsets.UTF_8);
                int versionIdx = home.indexOf(META_VERSION_PREFIX);
                if (versionIdx < 0) {
                    return;
                }
                String version = home.substring(versionIdx + META_VERSION_PREFIX.length(), 
                        versionIdx + META_VERSION_PREFIX.length() + 6);
                if (version.endsWith("\"")) {
                    version = version.substring(0, version.length() - 1);
                }
                for (String script : scripts) {
                    for (String suffix : Arrays.asList("", ".min")) {
                        String scriptName = script.replace(".js", suffix + ".js");
                        String scriptUrl = GITHUB_ROOT + version + scriptName;
                        String hash = hashes.get(version + scriptName); 
                        if (hash == null) {
                            try {
                                String githubScript = normalize(IOUtils.toString(new URL(scriptUrl), StandardCharsets.UTF_8));
                                if (StringUtils.isNotBlank(githubScript)) {
                                    hash = DigestUtils.sha1Hex(githubScript);
                                    hashes.put(version + scriptName, hash);
                                    lengths.put(version + scriptName, githubScript.length());
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                        String siteScript = normalize(IOUtils.toString(new URL(url + WOOCOMMERCE_PREFIX + scriptName), 
                                StandardCharsets.UTF_8));
                        if (StringUtils.isBlank(siteScript) || siteScript.contains("</body>") || siteScript.contains("</html>")) {
                            continue;
                        }
                        
                        String siteHash = DigestUtils.sha1Hex(siteScript);
                        
                        if (hash != null && !siteHash.equals(hash)) {
                            // check if the min.js is not actually expanded
                            if (scriptName.endsWith(".min.js")) {
                                siteScript = normalize(IOUtils.toString(new URL(url + WOOCOMMERCE_PREFIX + scriptName.replace(".min.js", ".js")), 
                                        StandardCharsets.UTF_8));
                                if (DigestUtils.sha1Hex(siteScript).equals(hashes.get(version + scriptName.replace(".min.js", ".js")))) {
                                    queue.put(Arrays.asList(url + WOOCOMMERCE_PREFIX + scriptName, "ok"));
                                    continue;
                                }
                            }
                            
                            if (Math.abs(siteScript.length() - lengths.get(version + scriptName)) < LENGTH_DIFF_THRESHOLD) {
                                queue.put(Arrays.asList(url + WOOCOMMERCE_PREFIX + scriptName, "ok"));
                                continue;
                            }
                            
                            System.out.println(normalize(IOUtils.toString(new URL(scriptUrl), StandardCharsets.UTF_8)));
                            System.out.println(siteScript);
                            System.out.println(url + WOOCOMMERCE_PREFIX + scriptName + " mismatch");
                            
                            queue.put(Arrays.asList(url + WOOCOMMERCE_PREFIX + scriptName, "mismatch"));
                        } else {
                            queue.put(Arrays.asList(url + WOOCOMMERCE_PREFIX + scriptName, "ok"));
                        }
                    }
                }
                Thread.sleep(100);
            } catch (UnknownHostException | ConnectException | SSLHandshakeException | FileNotFoundException | SocketTimeoutException ex) {
                problematicHosts.incrementAndGet();
            } catch (IOException | InterruptedException e) {
                if (e.getMessage().startsWith("Server returned HTTP response code")) {
                    problematicHosts.incrementAndGet();
                } else {
                    e.printStackTrace();
                }
            }
        });
        System.out.println("Problematic hosts: " + problematicHosts);
    }

    private static String normalize(String string) {
        // remove all whitespaces and all comments in case something has been just commented out
        return string.trim().replaceAll("\\s", "").replace("//", "");
    }
}
