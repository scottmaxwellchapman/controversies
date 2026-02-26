package net.familylawandprobate.controversies.storage;

import java.util.Map;

public interface DocumentStorageBackend {
    String put(String key, byte[] bytes) throws Exception;

    byte[] get(String key) throws Exception;

    void delete(String key) throws Exception;

    boolean exists(String key) throws Exception;

    Map<String, String> metadata(String key) throws Exception;
}
