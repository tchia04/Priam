/*
 * Copyright 2018 Netflix, Inc.
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
package com.netflix.priam.backupv2;

import com.netflix.priam.config.IConfiguration;
import com.netflix.priam.identity.InstanceIdentity;
import com.netflix.priam.utils.DateUtil;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import javax.inject.Inject;

/**
 * This is a utility class to get the backup location of the SSTables/Meta files with backup version
 * 2.0. TODO: All this functionality will be used when we have BackupUploadDownloadService. Created
 * by aagrawal on 6/5/18.
 */
public class PrefixGenerator {

    private final IConfiguration configuration;
    private final InstanceIdentity instanceIdentity;

    @Inject
    PrefixGenerator(IConfiguration configuration, InstanceIdentity instanceIdentity) {
        this.configuration = configuration;
        this.instanceIdentity = instanceIdentity;
    }

    public Path getPrefix() {
        return Paths.get(
                configuration.getBackupLocation(),
                configuration.getBackupPrefix(),
                getAppNameReverse(),
                instanceIdentity.getInstance().getToken());
    }

    public Path getSSTPrefix() {
        return getPrefix();
    }

    public Path getSSTLocation(
            Instant instant,
            String keyspaceName,
            String columnfamilyName,
            String prefix,
            String fileName) {
        return Paths.get(
                getPrefix().toString(),
                DateUtil.formatInstant(DateUtil.ddMMyyyyHHmm, instant),
                keyspaceName,
                columnfamilyName,
                prefix,
                fileName);
    }

    public Path getMetaPrefix() {
        return Paths.get(getPrefix().toString(), "META");
    }

    public Path getMetaLocation(Instant instant, String metaFileName) {
        return Paths.get(
                getMetaPrefix().toString(),
                DateUtil.formatInstant(DateUtil.ddMMyyyyHHmm, instant),
                metaFileName);
    }

    private String getAppNameReverse() {
        return new StringBuilder(configuration.getAppName()).reverse().toString();
    }

    // e.g. mc-3-big-Data.db or sample_cf-ka-7213-Index.db

    /**
     * Gives the prefix (common name) of the sstable components. Returns null if it is not sstable
     * component e.g. mc-3-big-Data.db or ks-cf-ka-7213-Index.db will return mc-3-big or
     * ks-cf-ka-7213
     *
     * @param fileName name of the file for common prefix
     * @return common prefix of the file, or null, if not identified as sstable component.
     */
    public static final String getSSTFileBase(String fileName) {
        String prefix = null;
        try {
            prefix = fileName.substring(0, fileName.lastIndexOf("-"));
        } catch (IndexOutOfBoundsException e) {
            // Do nothing
        }

        return prefix;
    }
}
