# Release reflection boundaries. Keep these in sync with the pinned dependencies.
# Do not keep entire application/dependency packages or suppress all R8 warnings.

# terminal-emulator JNI.java is resolved by Java_com_termux_terminal_JNI_* in
# third_party/terminal-emulator/src/main/jni/termux.c (name-based JNI, not registration).
-keep,allowoptimization class com.termux.terminal.JNI {
    native <methods>;
}

# LSP4J 0.24.0 AnnotationUtil scans runtime annotations and generic method types.
# DefaultLanguageClient/LanguageServer endpoints use dynamic proxies and delegates.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep @interface org.eclipse.lsp4j.jsonrpc.services.Json* { *; }
-keep @interface org.eclipse.lsp4j.jsonrpc.json.ResponseJsonAdapter { *; }
-keepclasseswithmembers,allowoptimization class * {
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest <methods>;
}
-keepclasseswithmembers,allowoptimization class * {
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification <methods>;
}
-keepclasseswithmembers,allowoptimization class * {
    @org.eclipse.lsp4j.jsonrpc.services.JsonDelegate <methods>;
}

# Gson's shipped consumer rules preserve its annotations, not LSP4J's unannotated
# wire fields. Restrict retention to protocol DTOs, not LSP4J implementation code.
# Keeping the class also preserves its generic signature under R8 full mode.
-keep,allowobfuscation class org.eclipse.lsp4j.* {
    <init>();
}
-keepclassmembers class org.eclipse.lsp4j.* {
    !static !transient <fields>;
}
-keep,allowobfuscation class org.eclipse.lsp4j.jsonrpc.messages.* {
    <init>();
}
-keepclassmembers class org.eclipse.lsp4j.jsonrpc.messages.* {
    !static !transient <fields>;
}
# EnumTypeAdapter reflects the exact field "value" and enum names. Numeric LSP
# values are not ordinal values, so renaming/removing this field corrupts the wire.
-keepclassmembers enum org.eclipse.lsp4j.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# TextMate 0.23.6 -> jcodings 1.0.63 loads registry names using Class.forName,
# then getField("INSTANCE"). Other encoder methods remain shrinkable/optimizable.
-keep,allowoptimization class org.jcodings.specific.*Encoding {
    public static ** INSTANCE;
}
-keep,allowoptimization class org.jcodings.transcode.specific.*Transcoder {
    public static ** INSTANCE;
}

# JSch 2.28.8 JSch.java config values are class names; Session/KeyPair/KeyExchange
# invoke their zero-argument constructors reflectively. This is the exact set of
# built-in config targets, not a keep-all for SSH classes or their members.
# Source: Maven com.github.mwiede:jsch:2.28.8, JSch.java static config block.
-keep,allowoptimization class com.jcraft.jsch.CipherNone { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DH25519 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DH25519MLKEM768 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DH25519SNTRUP761 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DH448 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHEC256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHEC256MLKEM768 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHEC384 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHEC384MLKEM1024 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHEC521 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG1 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG14 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG14224 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG14256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG15 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG15256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG15384 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG16 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG16384 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG17 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHG18 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHGEX1 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHGEX224 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHGEX256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHGEX384 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.DHGEX512 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthGSSAPIWithMIC { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthKeyboardInteractive { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthNone { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPassword { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPublicKey { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.Argon2 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.CAST128CBC { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.CAST128CTR { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.ChaCha20Poly1305 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.HMACRIPEMD160 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.HMACRIPEMD160ETM { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.HMACRIPEMD160OpenSSH { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.KeyPairGenEdDSA { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.MLKEM1024 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.MLKEM768 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.SCrypt { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.SEEDCBC { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.SNTRUP761 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.SignatureEd25519 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.SignatureEd448 { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.Twofish128CBC { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.Twofish128CTR { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.Twofish192CBC { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.Twofish192CTR { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.Twofish256CBC { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.Twofish256CTR { <init>(); }
-if class org.bouncycastle.crypto.params.AsymmetricKeyParameter
-keep,allowoptimization class com.jcraft.jsch.bc.XDH { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jbcrypt.JBCrypt { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES128CBC { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES128CTR { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES128GCM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES192CBC { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES192CTR { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES256CBC { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES256CTR { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.AES256GCM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.ARCFOUR { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.ARCFOUR128 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.ARCFOUR256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.BlowfishCBC { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.BlowfishCTR { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.DH { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.ECDHN { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACMD5 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACMD596 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACMD596ETM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACMD5ETM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA1 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA196 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA196ETM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA1ETM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA224SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA2562SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA256ETM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA256SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA384SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA512 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA512ETM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.HMACSHA512SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.KeyPairGenDSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.KeyPairGenECDSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.KeyPairGenEdDSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.KeyPairGenRSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.MD5 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.MLKEM1024 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.MLKEM768 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.PBKDF2 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.Random { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SHA1 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SHA224 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SHA256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SHA384 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SHA512 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureDSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureECDSA256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureECDSA384 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureECDSA521 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureEd25519 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureEd448 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureRSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureRSASHA224SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureRSASHA256 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureRSASHA256SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureRSASHA384SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureRSASHA512 { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.SignatureRSASHA512SSHCOM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.TripleDESCBC { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.TripleDESCTR { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.jce.XDH { <init>(); }
-if interface org.ietf.jgss.GSSContext
-keep,allowoptimization class com.jcraft.jsch.jgss.GSSContextKrb5 { <init>(); }
-if class com.jcraft.jzlib.ZStream
-keep,allowoptimization class com.jcraft.jsch.jzlib.Compression { <init>(); }
