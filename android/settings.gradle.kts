pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Krewd"
include(":app")

// Liquid glass is built from checkouts next to this repo (not on Maven yet): clone zainbits/vitreous,
// zainbits/cohesion and zainbits/meniscus (branch feature/liquid-glass) beside krewd/.
includeBuild("../../vitreous") {
    dependencySubstitution {
        substitute(module("io.github.zainbits:vitreous")).using(project(":vitreous"))
    }
}

// Meniscus with liquid glass lives on its feature/liquid-glass branch (not released yet), so the nav bar is
// built from the checkout next to this repo: ~/git-apps/meniscus on that branch.
includeBuild("../../meniscus") {
    dependencySubstitution {
        substitute(module("io.github.zainbits:meniscus")).using(project(":meniscus"))
    }
}
