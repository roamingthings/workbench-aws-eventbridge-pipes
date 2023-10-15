package de.roamingthings;

import software.amazon.awscdk.App;

public class Main {
    public static void main(final String[] args) {
        App app = new App();
        new AppStack(app, "WorkbenchEventBridgePipes", AppStack.AppStackProps.builder()
                .build());
        app.synth();
    }
}
