# VR review builds

The public debug keystore in this directory signs only the opt-in
com.limelight.vplus_debug.vrreview application. Its standard Android debug
password is intentionally public. It is not a release identity.

Use -PvrReviewBuild=true to enable this separate application ID, label and fixed
debug signature. The vr-build workflow sets this property through the Gradle
environment. Other build types and the existing application ID are unchanged.
