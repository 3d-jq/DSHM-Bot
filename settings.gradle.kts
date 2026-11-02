// DSHM Bot：原生 Android 客户端（Kotlin + Compose）。底层接入 DSH / DS Bot。
// 仓库结构：app/ 单模块；参考基线 DS-Bot / dsh-Go 只读存放于仓库外（见 .gitignore）。

pluginManagement {
    repositories {
        // 中国大陆优先走镜像，官方源保留回退。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "DSHM-Bot"
include(":app")
