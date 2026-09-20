package com.gateman.cctv.uploader.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class UploaderConfigTest {

    @Inject
    UploaderConfig config;

    @Test
    @DisplayName("Should inject UploaderConfig with expected defaults")
    void testConfigDefaults() {
        assertThat(config).isNotNull();
        assertThat(config.bufferDir()).isEqualTo("/mnt/buffer/cctv");
        assertThat(config.alistEndpoint()).isEqualTo("http://10.0.1.227:5244/dav");
        assertThat(config.alistUsername()).isEqualTo("gateman");
        assertThat(config.alistPassword()).isEqualTo("32565624");
        assertThat(config.remoteBaseDir()).isEqualTo("/Quark/CCTV_Records");
        assertThat(config.locationName()).isEqualTo("锦绣世家_客厅");
        assertThat(config.minFileAgeSeconds()).isEqualTo(60L);
        assertThat(config.maxConcurrentUploads()).isEqualTo(1);
        assertThat(config.cleanupPolicy()).isEqualTo("DELETE");
    }
}
