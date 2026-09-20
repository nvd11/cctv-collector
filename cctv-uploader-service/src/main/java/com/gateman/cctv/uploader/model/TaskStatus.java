package com.gateman.cctv.uploader.model;

/**
 * Lifecycle status of an individual cloud upload task.
 */
public enum TaskStatus {

    /**
     * File has been scanned, audited as stable, and is queued waiting for upload.
     */
    PENDING("Queued for upload"),

    /**
     * Actively streaming data to the remote Alist WebDAV endpoint.
     */
    UPLOADING("Actively uploading"),

    /**
     * Upload successfully finalized and verified on the cloud netdisk.
     */
    SUCCESS("Upload verified and confirmed"),

    /**
     * Upload encountered an error (timeout, network drop, or rate limit).
     */
    FAILED("Upload failed"),

    /**
     * Upload is in backoff delay before re-attempting.
     */
    RETRYING("In retry backoff delay"),

    /**
     * File was detected to already exist on remote storage with matching size.
     */
    SKIPPED("Already exists remotely, upload skipped");

    private final String description;

    TaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == SKIPPED;
    }
}
