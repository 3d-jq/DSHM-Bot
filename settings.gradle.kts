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
        // 本地仓库优先：镜像里没有的库从这儿找。
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // 阿里云镜像里没有 markdown 渲染器，HEAD 也会超时硬失败 ——
        // 指向本地的离线仓库（tools/offline-maven），里面是按 maven 布局放好的文件。
        maven { url = uri("file:///D:/program/DSHM Bot/tools/offline-maven") }
    }
}

rootProject.name = "DSHM-Bot"
include(":app")
