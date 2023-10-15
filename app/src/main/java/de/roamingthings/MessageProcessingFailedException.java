package de.roamingthings;

public class MessageProcessingFailedException extends IllegalStateException {

    public MessageProcessingFailedException(Throwable cause) {
        super(cause);
    }
}
