# Third-party notices

The runtime JAR contains only this project's classes and static resources. No third-party runtime library is bundled.

The project compiles against PortSwigger's Montoya API using a `compileOnly` Gradle dependency:

```text
net.portswigger.burp.extensions:montoya-api:2026.4
```

Burp Suite supplies the Montoya API at runtime. Use of Burp Suite and its extension API remains subject to PortSwigger's applicable license terms.

The lightweight build bootstrap scripts download Gradle 8.14.3 for build use; Gradle is not included in the extension JAR.
