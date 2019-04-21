/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.tasks;

import alpine.Config;
import alpine.event.framework.Event;
import alpine.event.framework.LoggableSubscriber;
import alpine.logging.Logger;
import alpine.notification.Notification;
import alpine.notification.NotificationLevel;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpUriRequest;
import org.dependencytrack.common.HttpClientPool;
import org.dependencytrack.event.DependencyCheckEvent;
import org.dependencytrack.event.NistMirrorEvent;
import org.dependencytrack.notification.NotificationConstants;
import org.dependencytrack.notification.NotificationGroup;
import org.dependencytrack.notification.NotificationScope;
import org.dependencytrack.parser.nvd.NvdParser;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.Date;
import java.util.zip.GZIPInputStream;

/**
 * Subscriber task that performs a mirror of the National Vulnerability Database.
 *
 * @author Steve Springett
 * @since 3.0.0
 */
public class NistMirrorTask implements LoggableSubscriber {

    public static final String NVD_MIRROR_DIR = Config.getInstance().getDataDirectorty().getAbsolutePath() + File.separator + "nist";
    private static final String CVE_XML_12_MODIFIED_URL = "https://nvd.nist.gov/feeds/xml/cve/1.2/nvdcve-modified.xml.gz";
    private static final String CVE_XML_20_MODIFIED_URL = "https://nvd.nist.gov/feeds/xml/cve/2.0/nvdcve-2.0-modified.xml.gz";
    private static final String CVE_XML_12_BASE_URL = "https://nvd.nist.gov/feeds/xml/cve/1.2/nvdcve-%d.xml.gz";
    private static final String CVE_XML_20_BASE_URL = "https://nvd.nist.gov/feeds/xml/cve/2.0/nvdcve-2.0-%d.xml.gz";
    private static final String CVE_JSON_10_MODIFIED_URL = "https://nvd.nist.gov/feeds/json/cve/1.0/nvdcve-1.0-modified.json.gz";
    private static final String CVE_JSON_10_BASE_URL = "https://nvd.nist.gov/feeds/json/cve/1.0/nvdcve-1.0-%d.json.gz";
    private static final int START_YEAR = 2002;
    private static final int END_YEAR = Calendar.getInstance().get(Calendar.YEAR);
    private File outputDir;

    private static final Logger LOGGER = Logger.getLogger(NistMirrorTask.class);

    private boolean mirroredWithoutErrors = true;

    /**
     * {@inheritDoc}
     */
    public void inform(final Event e) {
        if (e instanceof NistMirrorEvent) {
            LOGGER.info("Starting NIST mirroring task");
            final File mirrorPath = new File(NVD_MIRROR_DIR);
            setOutputDir(mirrorPath.getAbsolutePath());
            getAllFiles();
            LOGGER.info("NIST mirroring complete");

            // Publish a Dependency-Check UPDATE ONLY event to update its data directory.
            Event.dispatch(new DependencyCheckEvent(DependencyCheckEvent.Action.UPDATE_ONLY));
        }
    }

    /**
     * Download all NVD XML and JSON feeds from NIST.
     */
    private void getAllFiles() {
        final Date currentDate = new Date();
        LOGGER.info("Downloading files at " + currentDate);
        for (int i = START_YEAR; i <= END_YEAR; i++) {
            final String xml12BaseUrl = CVE_XML_12_BASE_URL.replace("%d", String.valueOf(i));
            final String xml20BaseUrl = CVE_XML_20_BASE_URL.replace("%d", String.valueOf(i));
            final String json10BaseUrl = CVE_JSON_10_BASE_URL.replace("%d", String.valueOf(i));
            doDownload(xml12BaseUrl);
            doDownload(xml20BaseUrl);
            doDownload(json10BaseUrl);
        }
        doDownload(CVE_XML_12_MODIFIED_URL);
        doDownload(CVE_XML_20_MODIFIED_URL);
        doDownload(CVE_JSON_10_MODIFIED_URL);

        if (mirroredWithoutErrors) {
            Notification.dispatch(new Notification()
                    .scope(NotificationScope.SYSTEM)
                    .group(NotificationGroup.DATASOURCE_MIRRORING)
                    .title(NotificationConstants.Title.NVD_MIRROR)
                    .content("Mirroring of the National Vulnerability Database completed successfully")
                    .level(NotificationLevel.INFORMATIONAL)
            );
        }
    }

    /**
     * Defines the output directory where the mirrored files will be stored.
     * Creates the directory if non-existent.
     * @param outputDirPath the target output directory path
     */
    private void setOutputDir(final String outputDirPath) {
        outputDir = new File(outputDirPath);
        if (!outputDir.exists()) {
            if (outputDir.mkdirs()) {
                LOGGER.info("Mirrored data directory created successfully");
            }
        }
    }

    /**
     * Performs a HTTP HEAD request to determine if a URL has updates since the last
     * time it was requested.
     * @param cveUrl the URL to perform a HTTP HEAD request on
     * @return the length of the content if it were to be downloaded
     */
    private long checkHead(final String cveUrl) {
        final HttpUriRequest request = new HttpHead(cveUrl);
        try (final CloseableHttpResponse response = HttpClientPool.getClient().execute(request)) {
            return Long.valueOf(response.getFirstHeader(HttpHeaders.CONTENT_LENGTH).getValue());
        } catch (IOException | NumberFormatException | NullPointerException e) {
            LOGGER.error("Failed to determine content length");
        }
        return 0;
    }

    /**
     * Performs a download of specified URL.
     * @param cveUrl the URL contents to download
     */
    private void doDownload(final String cveUrl) {
        File file;
        try {
            final URL url = new URL(cveUrl);
            String filename = url.getFile();
            filename = filename.substring(filename.lastIndexOf('/') + 1);
            file = new File(outputDir, filename).getAbsoluteFile();

            if (file.exists()) {
                final long fileSize = checkHead(cveUrl);
                if (file.length() == fileSize) {
                    LOGGER.info("Using cached version of " + filename);
                    return;
                }
            }

            LOGGER.info("Initiating download of " + url.toExternalForm());
            final HttpUriRequest request = new HttpGet(cveUrl);
            try (final CloseableHttpResponse response = HttpClientPool.getClient().execute(request)) {
                final StatusLine status = response.getStatusLine();
                if (status.getStatusCode() == 200) {
                    LOGGER.info("Downloading...");
                    try (InputStream in = response.getEntity().getContent()) {
                        file = new File(outputDir, filename);
                        FileUtils.copyInputStreamToFile(in, file);
                        uncompress(file);
                    }
                } else if (response.getStatusLine().getStatusCode() == 403) {
                    mirroredWithoutErrors = false;
                    final String detailMessage = "This may occur if the NVD is throttling connections due to excessive load or repeated " +
                            "connections from the same IP address or as a result of firewall or proxy authentication failures";
                    LOGGER.warn("Unable to download - HTTP Response 403: " + status.getReasonPhrase());
                    LOGGER.warn(detailMessage);
                    Notification.dispatch(new Notification()
                            .scope(NotificationScope.SYSTEM)
                            .group(NotificationGroup.DATASOURCE_MIRRORING)
                            .title(NotificationConstants.Title.NVD_MIRROR)
                            .content("An error occurred mirroring the contents of the National Vulnerability Database. Check log for details. HTTP Response: " + status.getStatusCode() + ". " + detailMessage)
                            .level(NotificationLevel.ERROR)
                    );
                } else {
                    mirroredWithoutErrors = false;
                    LOGGER.warn("Unable to download - HTTP Response " + status.getStatusCode() + ": " + status.getReasonPhrase());
                    Notification.dispatch(new Notification()
                            .scope(NotificationScope.SYSTEM)
                            .group(NotificationGroup.DATASOURCE_MIRRORING)
                            .title(NotificationConstants.Title.NVD_MIRROR)
                            .content("An error occurred mirroring the contents of the National Vulnerability Database. Check log for details. HTTP Response: " + status.getStatusCode())
                            .level(NotificationLevel.ERROR)
                    );
                }
            }
        } catch (IOException e) {
            mirroredWithoutErrors = false;
            LOGGER.error("Download failed : " + e.getMessage());
            Notification.dispatch(new Notification()
                    .scope(NotificationScope.SYSTEM)
                    .group(NotificationGroup.DATASOURCE_MIRRORING)
                    .title(NotificationConstants.Title.NVD_MIRROR)
                    .content("An error occurred mirroring the contents of the National Vulnerability Database. Check log for details. " + e.getMessage())
                    .level(NotificationLevel.ERROR)
            );
        }
    }

    /**
     * Extracts a GZip file.
     * @param file the file to extract
     */
    private void uncompress(final File file) {
        final byte[] buffer = new byte[1024];
        GZIPInputStream gzis = null;
        OutputStream out = null;
        try {
            LOGGER.info("Uncompressing " + file.getName());
            gzis = new GZIPInputStream(Files.newInputStream(file.toPath()));
            final File uncompressedFile = new File(file.getAbsolutePath().replaceAll(".gz", ""));
            out = Files.newOutputStream(uncompressedFile.toPath());
            int len;
            while ((len = gzis.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            final NvdParser parser = new NvdParser();
            parser.parse(uncompressedFile);
        } catch (IOException ex) {
            mirroredWithoutErrors = false;
            LOGGER.error("An error occurred uncompressing NVD payload", ex);
        } finally {
            close(gzis);
            close(out);
        }
    }

    /**
     * Closes a closable object.
     * @param object the object to close
     */
    private void close(final Closeable object) {
        if (object != null) {
            try {
                object.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing stream", e);
            }
        }
    }
}
