import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

// Port linea a linea de Crypto.kt (mismos algoritmos JCA) para verificar interoperabilidad con Python.
public class CryptoInterop {
    static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }
    static byte[] cat(byte[]... p) {
        int n = 0; for (byte[] b : p) n += b.length;
        byte[] o = new byte[n]; int pos = 0;
        for (byte[] b : p) { System.arraycopy(b, 0, o, pos, b.length); pos += b.length; }
        return o;
    }
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        byte[] prk = hmac(salt.length == 0 ? new byte[32] : salt, ikm);
        byte[] out = new byte[length]; byte[] t = new byte[0]; int pos = 0; int counter = 1;
        while (pos < length) {
            t = hmac(prk, cat(t, info, new byte[]{(byte) counter}));
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n); pos += n; counter++;
        }
        return out;
    }
    static byte[] fixed32(BigInteger v) {
        byte[] raw = v.toByteArray(); byte[] out = new byte[32];
        if (raw.length >= 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
    static byte[] seal(byte[] key, int sid, long ctr, byte[] plain) throws Exception {
        byte[] header = ByteBuffer.allocate(12).putInt(sid).putLong(ctr).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, header));
        c.updateAAD(header);
        return cat(header, c.doFinal(plain));
    }
    static byte[] open(byte[] key, byte[] frame) throws Exception {
        byte[] header = java.util.Arrays.copyOfRange(frame, 0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, header));
        c.updateAAD(header);
        return c.doFinal(frame, 12, frame.length - 12);
    }
    static byte[] unhex(String s) {
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return o;
    }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    public static void main(String[] a) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        // 1) HKDF vectors (32 y 4 bytes)
        String[] l = in.readLine().split(" ");
        byte[] ikm = unhex(l[0]), salt = unhex(l[1]), info = unhex(l[2]);
        System.out.println(hex(hkdf(ikm, salt, info, 32)));
        System.out.println(hex(hkdf(ikm, salt, info, 4)));
        // 2) Trama: Python cifra -> Java descifra; Java cifra -> Python descifra
        l = in.readLine().split(" ");
        byte[] key = unhex(l[0]);
        System.out.println(new String(open(key, unhex(l[1])), "UTF-8"));
        System.out.println(hex(seal(key, 0x01020304, 77L, "hola desde java".getBytes("UTF-8"))));
        // 3) ECDH P-256 + pairingMaterial
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] pubRaw = cat(new byte[]{4}, fixed32(pub.getW().getAffineX()), fixed32(pub.getW().getAffineY()));
        System.out.println(hex(pubRaw));
        byte[] peer = unhex(in.readLine().trim());
        ECPoint pt = new ECPoint(new BigInteger(1, java.util.Arrays.copyOfRange(peer, 1, 33)), new BigInteger(1, java.util.Arrays.copyOfRange(peer, 33, 65)));
        PublicKey peerKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(pt, pub.getParams()));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(kp.getPrivate()); ka.doPhase(peerKey, true);
        byte[] shared = ka.generateSecret();
        // Java hace el rol de "reloj": watchPub = pubRaw, serverPub = peer
        byte[] saltP = MessageDigest.getInstance("SHA-256").digest(cat(pubRaw, peer));
        byte[] secret = hkdf(shared, saltP, "airwatch pairing secret".getBytes(), 32);
        long n = ByteBuffer.wrap(hkdf(shared, saltP, "airwatch sas".getBytes(), 4)).getInt() & 0xFFFFFFFFL;
        System.out.println(hex(secret));
        System.out.println(String.format("%06d", n % 1000000L));
        byte[] devId = java.util.Arrays.copyOfRange(MessageDigest.getInstance("SHA-256").digest(pubRaw), 0, 8);
        System.out.println(hex(devId));
    }
}
