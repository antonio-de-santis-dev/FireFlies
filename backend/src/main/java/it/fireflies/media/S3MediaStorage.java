package it.fireflies.media;

import java.net.URI;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Storage S3-compatibile: Cloudflare R2 in produzione (candidato, C-07), MinIO o AWS S3 come alternative.
 * Le varianti hanno chiavi nuove a ogni caricamento o sostituzione, quindi si possono servire dal CDN con
 * cache lunghissima ({@code immutable}).
 */
final class S3MediaStorage implements MediaStorage {

    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client s3;
    private final String bucket;
    private final String endpoint;

    S3MediaStorage(MediaProperties.S3 config) {
        this(S3Client.builder()
                .endpointOverride(URI.create(config.endpoint()))
                .region(Region.of(config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyle())
                        .chunkedEncodingEnabled(false) // R2 non accetta il caricamento «aws-chunked»: corpo intero firmato
                        .build())
                // R2 e MinIO non gestiscono tutti i checksum aggiuntivi introdotti dall'SDK 2.30: solo se richiesti.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .build(), config.bucket(), config.endpoint());
    }

    S3MediaStorage(S3Client s3, String bucket, String endpoint) {
        this.s3 = s3;
        this.bucket = bucket;
        this.endpoint = endpoint;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType(contentType).cacheControl(CACHE_CONTROL)
                    .contentLength((long) content.length).build(), RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new StorageException("Caricamento su S3 non riuscito: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new StorageException("Cancellazione su S3 non riuscita: " + key, e);
        }
    }

    @Override
    public String describe() {
        return "S3 " + endpoint + " bucket " + bucket;
    }
}
