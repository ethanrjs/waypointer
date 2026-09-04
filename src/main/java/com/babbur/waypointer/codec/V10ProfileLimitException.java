package com.babbur.waypointer.codec;

import java.io.IOException;

/** A candidate whose semantic or physical frame exceeds the bounded V10 profile. */
final class V10ProfileLimitException extends IOException {
    V10ProfileLimitException(String message) {
        super(message);
    }
}
