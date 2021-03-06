/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.priam.backup;

import com.google.inject.ImplementedBy;
import com.netflix.priam.aws.S3BackupPath;
import com.netflix.priam.config.IConfiguration;
import com.netflix.priam.identity.InstanceIdentity;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.ParseException;
import java.util.Date;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ImplementedBy(S3BackupPath.class)
public abstract class AbstractBackupPath implements Comparable<AbstractBackupPath> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractBackupPath.class);
    private static final String FMT = "yyyyMMddHHmm";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormat.forPattern(FMT);
    public static final char PATH_SEP = File.separatorChar;

    public enum BackupFileType {
        SNAP,
        SST,
        CL,
        META,
        META_V2;

        public static boolean isDataFile(BackupFileType type) {
            return type != BackupFileType.META
                    && type != BackupFileType.META_V2
                    && type != BackupFileType.CL;
        }
    }

    protected BackupFileType type;
    protected String clusterName;
    protected String keyspace;
    protected String columnFamily;
    protected String fileName;
    protected String baseDir;
    protected String token;
    protected String region;
    protected Date time;
    private long size; // uncompressed file size
    private long compressedFileSize = 0;
    protected final InstanceIdentity instanceIdentity;
    protected final IConfiguration config;
    private File backupFile;
    private long lastModified = 0;
    private Date uploadedTs;

    public AbstractBackupPath(IConfiguration config, InstanceIdentity instanceIdentity) {
        this.instanceIdentity = instanceIdentity;
        this.config = config;
    }

    public static String formatDate(Date d) {
        return new DateTime(d).toString(FMT);
    }

    public Date parseDate(String s) {
        return DATE_FORMAT.parseDateTime(s).toDate();
    }

    public InputStream localReader() throws IOException {
        assert backupFile != null;
        InputStream ret = null;

        while (true) {
            if (ret != null) {
                ret.close();
            }

            lastModified = backupFile.lastModified();
            ret = new RafInputStream(new RandomAccessFile(backupFile, "r"));

            // Verify that the file hasn't changed since we opened it.
            // We could avoid this flow by using the fstat() system call,
            // but I see no way to do that (easily) from the JVM.
            // The JVM returns the last modified time in milliseconds,
            // but on Linux systems tested, it appears to be using the
            // stat.st_mtime result, which is accurate only to seconds.
            if (backupFile.lastModified() == lastModified) {
                break;
            }
        }

        return ret;
    }

    public void parseLocal(File file, BackupFileType type) throws ParseException {
        // TODO cleanup.
        this.backupFile = file;

        String rpath =
                new File(config.getDataFileLocation()).toURI().relativize(file.toURI()).getPath();
        String[] elements = rpath.split("" + PATH_SEP);
        this.clusterName = config.getAppName();
        this.baseDir = config.getBackupLocation();
        this.region = instanceIdentity.getInstanceInfo().getRegion();
        this.token = instanceIdentity.getInstance().getToken();
        this.type = type;
        if (BackupFileType.isDataFile(type)) {
            this.keyspace = elements[0];
            this.columnFamily = elements[1];
        }
        if (type == BackupFileType.SNAP) time = parseDate(elements[3]);
        if (type == BackupFileType.SST || type == BackupFileType.CL)
            time = new Date(file.lastModified());
        this.fileName = file.getName();
        this.size = file.length();
    }

    /** Given a date range, find a common string prefix Eg: 20120212, 20120213 = 2012021 */
    protected String match(Date start, Date end) {
        String sString = formatDate(start);
        String eString = formatDate(end);
        int diff = StringUtils.indexOfDifference(sString, eString);
        if (diff < 0) return sString;
        return sString.substring(0, diff);
    }

    /** Local restore file */
    public File newRestoreFile() {
        StringBuilder buff = new StringBuilder();
        if (type == BackupFileType.CL) {
            buff.append(config.getBackupCommitLogLocation()).append(PATH_SEP);
        } else {
            buff.append(config.getDataFileLocation()).append(PATH_SEP);
            if (type != BackupFileType.META && type != BackupFileType.META_V2)
                buff.append(keyspace).append(PATH_SEP).append(columnFamily).append(PATH_SEP);
        }

        buff.append(fileName);

        File return_ = new File(buff.toString());
        File parent = new File(return_.getParent());
        if (!parent.exists()) parent.mkdirs();
        return return_;
    }

    @Override
    public int compareTo(AbstractBackupPath o) {
        return getRemotePath().compareTo(o.getRemotePath());
    }

    @Override
    public boolean equals(Object obj) {
        return obj.getClass().equals(this.getClass())
                && getRemotePath().equals(((AbstractBackupPath) obj).getRemotePath());
    }

    /** Get remote prefix for this path object */
    public abstract String getRemotePath();

    /** Parses a fully constructed remote path */
    public abstract void parseRemote(String remoteFilePath);

    /** Parses paths with just token prefixes */
    public abstract void parsePartialPrefix(String remoteFilePath);

    /**
     * Provides a common prefix that matches all objects that fall between the start and end time
     */
    public abstract String remotePrefix(Date start, Date end, String location);

    /** Provides the cluster prefix */
    public abstract String clusterPrefix(String location);

    public BackupFileType getType() {
        return type;
    }

    public void setType(BackupFileType type) {
        this.type = type;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getKeyspace() {
        return keyspace;
    }

    public String getColumnFamily() {
        return columnFamily;
    }

    public String getFileName() {
        return fileName;
    }

    public String getBaseDir() {
        return baseDir;
    }

    public String getToken() {
        return token;
    }

    public String getRegion() {
        return region;
    }

    public Date getTime() {
        return time;
    }

    /*
    @return original, uncompressed file size
     */
    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getCompressedFileSize() {
        return this.compressedFileSize;
    }

    public void setCompressedFileSize(long val) {
        this.compressedFileSize = val;
    }

    public File getBackupFile() {
        return backupFile;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public InstanceIdentity getInstanceIdentity() {
        return this.instanceIdentity;
    }

    public void setUploadedTs(Date uploadedTs) {
        this.uploadedTs = uploadedTs;
    }

    public Date getUploadedTs() {
        return this.uploadedTs;
    }

    public long getLastModified() {
        return lastModified;
    }

    public static class RafInputStream extends InputStream implements AutoCloseable {
        private final RandomAccessFile raf;

        public RafInputStream(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public synchronized int read(byte[] bytes, int off, int len) throws IOException {
            return raf.read(bytes, off, len);
        }

        @Override
        public void close() {
            FileUtils.closeQuietly(raf);
        }

        @Override
        public int read() throws IOException {
            return 0;
        }
    }

    @Override
    public String toString() {
        return "From: " + getRemotePath() + " To: " + newRestoreFile().getPath();
    }
}
