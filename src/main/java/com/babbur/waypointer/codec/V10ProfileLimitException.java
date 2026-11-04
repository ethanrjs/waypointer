package com.babbur.waypointer.codec;

import java.io.IOException;

final class V10ProfileLimitException extends IOException {
    V10ProfileLimitException(String message) {
        super(message);
    }
}
