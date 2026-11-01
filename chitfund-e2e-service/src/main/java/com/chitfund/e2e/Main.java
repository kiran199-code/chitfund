package com.chitfund.e2e;

/** Starts the web page and API. Refuses to start when its settings are missing or it is not told it is in a test environment. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config cfg = new Config(System.getenv());
        var problems = cfg.problems();
        if (!problems.isEmpty()) {
            System.err.println("The e2e service cannot start:");
            problems.forEach(p -> System.err.println(" - " + p));
            System.exit(1);
        }
        Http http = Http.standard();
        RunManager runs = new RunManager(cfg, http, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        });
        int port = new WebServer(cfg, runs).start(cfg.port);
        System.out.println("e2e service listening on port " + port + " " + cfg);
    }
}
