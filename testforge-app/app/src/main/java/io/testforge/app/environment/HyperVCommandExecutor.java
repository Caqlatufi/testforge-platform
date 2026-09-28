package io.testforge.app.environment;

interface HyperVCommandExecutor {
    boolean supported();
    String state(String vmName);
    String start(String vmName);
}
