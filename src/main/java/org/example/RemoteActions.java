package org.example;

import java.nio.file.Path;

interface RemoteActions {
    void synchronize(String server, Path path) throws Exception;
    void delete(String server, Path path) throws Exception;
}
