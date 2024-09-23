package org.taniwha.service;

import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.Base64;

// Host the config of the principal set up in the kerberos system
@Getter
@Service
public class PrincipalService {

    private byte[] keytab;

    public void setKeytab(String base64Keytab) {
        this.keytab = decodeBase64Keytab(base64Keytab);
    }

    private byte[] decodeBase64Keytab(String base64Keytab) {
        return Base64.getDecoder().decode(base64Keytab);
    }
}
