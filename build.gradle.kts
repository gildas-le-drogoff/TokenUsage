// Build-time (AGP) transitive dependencies flagged by Dependabot. They are not
// shipped in the APK; constraining them here lets Dependabot see and bump them.
buildscript {
    dependencyLocking {
        lockAllConfigurations()
    }
    dependencies {
        constraints {
            classpath("com.google.guava:guava:33.8.0-jre")
            classpath("org.apache.commons:commons-lang3:3.22.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.87")
            classpath("org.bouncycastle:bcprov-jdk18on:1.87")
            classpath("org.freemarker:freemarker:2.3.35")
            classpath("org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
}
