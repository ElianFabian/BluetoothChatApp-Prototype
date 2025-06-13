plugins {
	alias(libs.plugins.androidApplication)
	alias(libs.plugins.jetbrainsKotlinAndroid)
	alias(libs.plugins.kotlinParcelize)
}

android {
	namespace = "com.elianfabian.bluetoothchatapp_prototype"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.elianfabian.bluetoothchatapp_prototype"
		minSdk = 24
		targetSdk = 35
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		vectorDrawables {
			useSupportLibrary = true
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			signingConfig = signingConfigs.getByName("debug")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}
	kotlinOptions {
		jvmTarget = "1.8"
	}
	buildFeatures {
		compose = true
		buildConfig = true
	}
	composeOptions {
		kotlinCompilerExtensionVersion = "1.5.1"
	}
	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}
	}
}

dependencies {

	implementation(libs.simpleStack)
	implementation(libs.simpleStackExtensions)
	implementation(libs.simpleStackCompose)
	implementation(libs.flowCombineTuple)

	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycleProcess)
	implementation(libs.androidx.lifecycleRuntimeKtx)
	implementation(libs.androidx.lifecycleRuntimeCompose)
	implementation(libs.androidx.activityCompose)
	implementation(platform(libs.androidx.composeBom))
	implementation(libs.androidx.ui)
	implementation(libs.androidx.ui.graphics)
	implementation(libs.androidx.ui.toolingPreview)
	implementation(libs.androidx.material3)
	implementation(libs.androidx.composeMaterialIconsExtended)
	implementation(libs.googlePlayServicesLocation)


	testImplementation(libs.junit)

	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espressoCore)
	androidTestImplementation(platform(libs.androidx.composeBom))
	androidTestImplementation(libs.androidx.ui.testJunit4)

	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.testManifest)
}
