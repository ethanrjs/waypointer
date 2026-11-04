package com.babbur.waypointer.update;

import java.net.URI;
import java.util.List;

record UpdateRelease(String version, String fileName, URI url, long size, String sha512,
                     List<String> changelog) {
}
