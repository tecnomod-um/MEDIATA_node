package org.taniwha.privacy;

import java.io.IOException;

// Local site operator command
public final class DiscoveryDatasetRegistration {
    private static final String COMMAND = "--register-discovery-dataset";
    private static final String PATH_OPTION = "--app.path=";

    private DiscoveryDatasetRegistration() {}

    public static boolean requested(String[] args) {
        return args.length > 0 && COMMAND.equals(args[0]);
    }

    public static int run(String[] args) {
        if (args.length < 2 || args.length > 3
                || (args.length == 3 && !args[2].startsWith(PATH_OPTION))) {
            System.err.println("Usage: " + COMMAND + " <dataset.csv|dataset.xlsx> [--app.path=<node-directory>]");
            return 2;
        }
        String base = args.length == 3 ? args[2].substring(PATH_OPTION.length())
                : System.getenv().getOrDefault("APP_PATH", "/taniwha");
        if (base.isBlank()) {
            System.err.println("Specify the running node's data directory.");
            return 2;
        }
        try {
            DiscoveryQueryLedger.openExisting(base).registerDataset(args[1]);
            System.out.println("Dataset is registered for Discovery.");
            return 0;
        } catch (DiscoveryQueryLedger.Blocked blocked) {
            System.err.println("Registration refused. The dataset changed at an existing path, or the node ledger requires review.");
        } catch (IllegalArgumentException invalid) {
            System.err.println("Specify a valid node directory and dataset filename.");
        } catch (IOException failure) {
            System.err.println("Could not register the dataset. Check the node directory, file and permissions.");
        }
        return 1;
    }
}
