package com.ivieleague.kbuild.ios

import java.io.File
import java.util.UUID

/**
 * Generates Xcode project files (.xcodeproj) for iOS apps.
 *
 * Xcode projects are directories containing a project.pbxproj file that defines
 * the build configuration, targets, and file references.
 *
 * This generator creates minimal but functional Xcode projects that can:
 * - Build iOS apps with Swift code
 * - Link against Kotlin/Native XCFrameworks via SPM
 * - Run on simulators and devices
 */
class XcodeProject(
    val name: String,
    val bundleId: String,
    val deploymentTarget: String = "14.0",
    val swiftVersion: String = "5.0",
    val organizationName: String = "Organization",
    private var developmentTeam: String? = null
) {
    /**
     * Configure code signing for the project.
     *
     * @param teamId The Apple Developer Team ID (10-character alphanumeric)
     * @return This project for chaining
     */
    fun configureCodeSigning(teamId: String): XcodeProject {
        this.developmentTeam = teamId
        return this
    }

    /**
     * Auto-detect and configure code signing.
     *
     * Uses IosCodeSigning to find available development teams and selects one.
     *
     * @return This project for chaining, or throws if no signing identity found
     */
    fun autoConfigureCodeSigning(): XcodeProject {
        val team = IosCodeSigning.autoSelectTeam()
            ?: throw IllegalStateException(
                "No code signing identity found. " +
                "Please sign into Xcode with your Apple Developer account."
            )
        this.developmentTeam = team.id
        println("Auto-configured code signing with team: ${team.name} (${team.id})")
        return this
    }

    /**
     * Get the configured development team ID.
     */
    fun getDevelopmentTeam(): String? = developmentTeam

    private val objects = mutableMapOf<String, PBXObject>()
    private val rootObjectId = generateId()

    // Well-known group IDs
    private val mainGroupId = generateId()
    private val productsGroupId = generateId()
    private val sourcesGroupId = generateId()
    private val frameworksGroupId = generateId()

    // Build configuration IDs
    private val projectDebugConfigId = generateId()
    private val projectReleaseConfigId = generateId()
    private val projectConfigListId = generateId()
    private val targetDebugConfigId = generateId()
    private val targetReleaseConfigId = generateId()
    private val targetConfigListId = generateId()

    // Target and phase IDs
    private val appTargetId = generateId()
    private val sourcesBuildPhaseId = generateId()
    private val frameworksBuildPhaseId = generateId()
    private val resourcesBuildPhaseId = generateId()

    // Product reference
    private val appProductId = generateId()

    private val fileRefs = mutableListOf<FileRef>()
    private val buildFiles = mutableListOf<BuildFile>()

    private data class FileRef(
        val id: String,
        val path: String,
        val name: String,
        val fileType: String,
        val sourceTree: String = "\"<group>\""
    )

    private data class BuildFile(
        val id: String,
        val fileRefId: String,
        val phase: BuildPhase
    )

    enum class BuildPhase { SOURCES, FRAMEWORKS, RESOURCES }

    /**
     * Add a Swift source file to the project.
     */
    fun addSwiftFile(relativePath: String): XcodeProject {
        val id = generateId()
        val buildId = generateId()
        val name = File(relativePath).name

        fileRefs.add(FileRef(id, relativePath, name, "sourcecode.swift"))
        buildFiles.add(BuildFile(buildId, id, BuildPhase.SOURCES))
        return this
    }

    /**
     * Add a storyboard file to the project.
     */
    fun addStoryboard(relativePath: String): XcodeProject {
        val id = generateId()
        val buildId = generateId()
        val name = File(relativePath).name

        fileRefs.add(FileRef(id, relativePath, name, "file.storyboard"))
        buildFiles.add(BuildFile(buildId, id, BuildPhase.RESOURCES))
        return this
    }

    /**
     * Add an Info.plist file (not added to build phases, just referenced).
     */
    fun addInfoPlist(relativePath: String): XcodeProject {
        val id = generateId()
        val name = File(relativePath).name

        fileRefs.add(FileRef(id, relativePath, name, "text.plist.xml"))
        return this
    }

    /**
     * Add an asset catalog to the project.
     */
    fun addAssetCatalog(relativePath: String): XcodeProject {
        val id = generateId()
        val buildId = generateId()
        val name = File(relativePath).name

        fileRefs.add(FileRef(id, relativePath, name, "folder.assetcatalog"))
        buildFiles.add(BuildFile(buildId, id, BuildPhase.RESOURCES))
        return this
    }

    /**
     * Generate the project.pbxproj content.
     */
    fun generate(): String = buildString {
        appendLine("// !${'$'}*UTF8*${'$'}!")
        appendLine("{")
        appendLine("\tarchiveVersion = 1;")
        appendLine("\tclasses = {")
        appendLine("\t};")
        appendLine("\tobjectVersion = 56;")
        appendLine("\tobjects = {")
        appendLine()

        // PBXBuildFile section
        appendLine("/* Begin PBXBuildFile section */")
        for (bf in buildFiles) {
            val fileRef = fileRefs.find { it.id == bf.fileRefId }!!
            appendLine("\t\t${bf.id} /* ${fileRef.name} in ${bf.phase.name.lowercase().replaceFirstChar { it.uppercase() }} */ = {isa = PBXBuildFile; fileRef = ${bf.fileRefId} /* ${fileRef.name} */; };")
        }
        appendLine("/* End PBXBuildFile section */")
        appendLine()

        // PBXFileReference section
        appendLine("/* Begin PBXFileReference section */")
        // App product
        appendLine("\t\t$appProductId /* $name.app */ = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = \"$name.app\"; sourceTree = BUILT_PRODUCTS_DIR; };")
        for (ref in fileRefs) {
            appendLine("\t\t${ref.id} /* ${ref.name} */ = {isa = PBXFileReference; lastKnownFileType = ${ref.fileType}; path = \"${ref.path}\"; sourceTree = ${ref.sourceTree}; };")
        }
        appendLine("/* End PBXFileReference section */")
        appendLine()

        // PBXFrameworksBuildPhase section
        appendLine("/* Begin PBXFrameworksBuildPhase section */")
        val frameworkBuildFiles = buildFiles.filter { it.phase == BuildPhase.FRAMEWORKS }
        appendLine("\t\t$frameworksBuildPhaseId /* Frameworks */ = {")
        appendLine("\t\t\tisa = PBXFrameworksBuildPhase;")
        appendLine("\t\t\tbuildActionMask = 2147483647;")
        appendLine("\t\t\tfiles = (")
        for (bf in frameworkBuildFiles) {
            val fileRef = fileRefs.find { it.id == bf.fileRefId }!!
            appendLine("\t\t\t\t${bf.id} /* ${fileRef.name} in Frameworks */,")
        }
        appendLine("\t\t\t);")
        appendLine("\t\t\trunOnlyForDeploymentPostprocessing = 0;")
        appendLine("\t\t};")
        appendLine("/* End PBXFrameworksBuildPhase section */")
        appendLine()

        // PBXGroup section
        appendLine("/* Begin PBXGroup section */")
        // Main group
        appendLine("\t\t$mainGroupId = {")
        appendLine("\t\t\tisa = PBXGroup;")
        appendLine("\t\t\tchildren = (")
        appendLine("\t\t\t\t$sourcesGroupId /* $name */,")
        appendLine("\t\t\t\t$productsGroupId /* Products */,")
        if (frameworkBuildFiles.isNotEmpty()) {
            appendLine("\t\t\t\t$frameworksGroupId /* Frameworks */,")
        }
        appendLine("\t\t\t);")
        appendLine("\t\t\tsourceTree = \"<group>\";")
        appendLine("\t\t};")

        // Products group
        appendLine("\t\t$productsGroupId /* Products */ = {")
        appendLine("\t\t\tisa = PBXGroup;")
        appendLine("\t\t\tchildren = (")
        appendLine("\t\t\t\t$appProductId /* $name.app */,")
        appendLine("\t\t\t);")
        appendLine("\t\t\tname = Products;")
        appendLine("\t\t\tsourceTree = \"<group>\";")
        appendLine("\t\t};")

        // Sources group
        appendLine("\t\t$sourcesGroupId /* $name */ = {")
        appendLine("\t\t\tisa = PBXGroup;")
        appendLine("\t\t\tchildren = (")
        for (ref in fileRefs) {
            appendLine("\t\t\t\t${ref.id} /* ${ref.name} */,")
        }
        appendLine("\t\t\t);")
        appendLine("\t\t\tpath = \"$name\";")
        appendLine("\t\t\tsourceTree = \"<group>\";")
        appendLine("\t\t};")

        // Frameworks group (if any)
        if (frameworkBuildFiles.isNotEmpty()) {
            appendLine("\t\t$frameworksGroupId /* Frameworks */ = {")
            appendLine("\t\t\tisa = PBXGroup;")
            appendLine("\t\t\tchildren = (")
            for (bf in frameworkBuildFiles) {
                val fileRef = fileRefs.find { it.id == bf.fileRefId }!!
                appendLine("\t\t\t\t${bf.fileRefId} /* ${fileRef.name} */,")
            }
            appendLine("\t\t\t);")
            appendLine("\t\t\tname = Frameworks;")
            appendLine("\t\t\tsourceTree = \"<group>\";")
            appendLine("\t\t};")
        }
        appendLine("/* End PBXGroup section */")
        appendLine()

        // PBXNativeTarget section
        appendLine("/* Begin PBXNativeTarget section */")
        appendLine("\t\t$appTargetId /* $name */ = {")
        appendLine("\t\t\tisa = PBXNativeTarget;")
        appendLine("\t\t\tbuildConfigurationList = $targetConfigListId /* Build configuration list for PBXNativeTarget \"$name\" */;")
        appendLine("\t\t\tbuildPhases = (")
        appendLine("\t\t\t\t$sourcesBuildPhaseId /* Sources */,")
        appendLine("\t\t\t\t$frameworksBuildPhaseId /* Frameworks */,")
        appendLine("\t\t\t\t$resourcesBuildPhaseId /* Resources */,")
        appendLine("\t\t\t);")
        appendLine("\t\t\tbuildRules = (")
        appendLine("\t\t\t);")
        appendLine("\t\t\tdependencies = (")
        appendLine("\t\t\t);")
        appendLine("\t\t\tname = \"$name\";")
        appendLine("\t\t\tpackageProductDependencies = (")
        appendLine("\t\t\t);")
        appendLine("\t\t\tproductName = \"$name\";")
        appendLine("\t\t\tproductReference = $appProductId /* $name.app */;")
        appendLine("\t\t\tproductType = \"com.apple.product-type.application\";")
        appendLine("\t\t};")
        appendLine("/* End PBXNativeTarget section */")
        appendLine()

        // PBXProject section
        appendLine("/* Begin PBXProject section */")
        appendLine("\t\t$rootObjectId /* Project object */ = {")
        appendLine("\t\t\tisa = PBXProject;")
        appendLine("\t\t\tattributes = {")
        appendLine("\t\t\t\tBuildIndependentTargetsInParallel = 1;")
        appendLine("\t\t\t\tLastSwiftUpdateCheck = 1500;")
        appendLine("\t\t\t\tLastUpgradeCheck = 1500;")
        appendLine("\t\t\t\tORGANIZATIONNAME = \"$organizationName\";")
        appendLine("\t\t\t\tTargetAttributes = {")
        appendLine("\t\t\t\t\t$appTargetId = {")
        appendLine("\t\t\t\t\t\tCreatedOnToolsVersion = 15.0;")
        appendLine("\t\t\t\t\t};")
        appendLine("\t\t\t\t};")
        appendLine("\t\t\t};")
        appendLine("\t\t\tbuildConfigurationList = $projectConfigListId /* Build configuration list for PBXProject \"$name\" */;")
        appendLine("\t\t\tcompatibilityVersion = \"Xcode 14.0\";")
        appendLine("\t\t\tdevelopmentRegion = en;")
        appendLine("\t\t\thasScannedForEncodings = 0;")
        appendLine("\t\t\tknownRegions = (")
        appendLine("\t\t\t\ten,")
        appendLine("\t\t\t\tBase,")
        appendLine("\t\t\t);")
        appendLine("\t\t\tmainGroup = $mainGroupId;")
        appendLine("\t\t\tproductRefGroup = $productsGroupId /* Products */;")
        appendLine("\t\t\tprojectDirPath = \"\";")
        appendLine("\t\t\tprojectRoot = \"\";")
        appendLine("\t\t\ttargets = (")
        appendLine("\t\t\t\t$appTargetId /* $name */,")
        appendLine("\t\t\t);")
        appendLine("\t\t};")
        appendLine("/* End PBXProject section */")
        appendLine()

        // PBXResourcesBuildPhase section
        appendLine("/* Begin PBXResourcesBuildPhase section */")
        val resourceBuildFiles = buildFiles.filter { it.phase == BuildPhase.RESOURCES }
        appendLine("\t\t$resourcesBuildPhaseId /* Resources */ = {")
        appendLine("\t\t\tisa = PBXResourcesBuildPhase;")
        appendLine("\t\t\tbuildActionMask = 2147483647;")
        appendLine("\t\t\tfiles = (")
        for (bf in resourceBuildFiles) {
            val fileRef = fileRefs.find { it.id == bf.fileRefId }!!
            appendLine("\t\t\t\t${bf.id} /* ${fileRef.name} in Resources */,")
        }
        appendLine("\t\t\t);")
        appendLine("\t\t\trunOnlyForDeploymentPostprocessing = 0;")
        appendLine("\t\t};")
        appendLine("/* End PBXResourcesBuildPhase section */")
        appendLine()

        // PBXSourcesBuildPhase section
        appendLine("/* Begin PBXSourcesBuildPhase section */")
        val sourceBuildFiles = buildFiles.filter { it.phase == BuildPhase.SOURCES }
        appendLine("\t\t$sourcesBuildPhaseId /* Sources */ = {")
        appendLine("\t\t\tisa = PBXSourcesBuildPhase;")
        appendLine("\t\t\tbuildActionMask = 2147483647;")
        appendLine("\t\t\tfiles = (")
        for (bf in sourceBuildFiles) {
            val fileRef = fileRefs.find { it.id == bf.fileRefId }!!
            appendLine("\t\t\t\t${bf.id} /* ${fileRef.name} in Sources */,")
        }
        appendLine("\t\t\t);")
        appendLine("\t\t\trunOnlyForDeploymentPostprocessing = 0;")
        appendLine("\t\t};")
        appendLine("/* End PBXSourcesBuildPhase section */")
        appendLine()

        // XCBuildConfiguration section
        appendLine("/* Begin XCBuildConfiguration section */")

        // Project Debug
        appendLine("\t\t$projectDebugConfigId /* Debug */ = {")
        appendLine("\t\t\tisa = XCBuildConfiguration;")
        appendLine("\t\t\tbuildSettings = {")
        appendLine("\t\t\t\tALWAYS_SEARCH_USER_PATHS = NO;")
        appendLine("\t\t\t\tASYNCRONOUS_SYMBOL_EXPORT = YES;")
        appendLine("\t\t\t\tCLANG_ANALYZER_NONNULL = YES;")
        appendLine("\t\t\t\tCLANG_ANALYZER_NUMBER_OBJECT_CONVERSION = YES_AGGRESSIVE;")
        appendLine("\t\t\t\tCLANG_CXX_LANGUAGE_STANDARD = \"gnu++20\";")
        appendLine("\t\t\t\tCLANG_ENABLE_MODULES = YES;")
        appendLine("\t\t\t\tCLANG_ENABLE_OBJC_ARC = YES;")
        appendLine("\t\t\t\tCLANG_ENABLE_OBJC_WEAK = YES;")
        appendLine("\t\t\t\tCLANG_WARN_BLOCK_CAPTURE_AUTORELEASING = YES;")
        appendLine("\t\t\t\tCLANG_WARN_BOOL_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_COMMA = YES;")
        appendLine("\t\t\t\tCLANG_WARN_CONSTANT_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS = YES;")
        appendLine("\t\t\t\tCLANG_WARN_DIRECT_OBJC_ISA_USAGE = YES_ERROR;")
        appendLine("\t\t\t\tCLANG_WARN_DOCUMENTATION_COMMENTS = YES;")
        appendLine("\t\t\t\tCLANG_WARN_EMPTY_BODY = YES;")
        appendLine("\t\t\t\tCLANG_WARN_ENUM_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_INFINITE_RECURSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_INT_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_NON_LITERAL_NULL_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF = YES;")
        appendLine("\t\t\t\tCLANG_WARN_OBJC_LITERAL_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_OBJC_ROOT_CLASS = YES_ERROR;")
        appendLine("\t\t\t\tCLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER = YES;")
        appendLine("\t\t\t\tCLANG_WARN_RANGE_LOOP_ANALYSIS = YES;")
        appendLine("\t\t\t\tCLANG_WARN_STRICT_PROTOTYPES = YES;")
        appendLine("\t\t\t\tCLANG_WARN_SUSPICIOUS_MOVE = YES;")
        appendLine("\t\t\t\tCLANG_WARN_UNGUARDED_AVAILABILITY = YES_AGGRESSIVE;")
        appendLine("\t\t\t\tCLANG_WARN_UNREACHABLE_CODE = YES;")
        appendLine("\t\t\t\tCLANG_WARN__DUPLICATE_METHOD_MATCH = YES;")
        appendLine("\t\t\t\tCOPY_PHASE_STRIP = NO;")
        appendLine("\t\t\t\tDEBUG_INFORMATION_FORMAT = dwarf;")
        appendLine("\t\t\t\tENABLE_STRICT_OBJC_MSGSEND = YES;")
        appendLine("\t\t\t\tENABLE_TESTABILITY = YES;")
        appendLine("\t\t\t\tENABLE_USER_SCRIPT_SANDBOXING = YES;")
        appendLine("\t\t\t\tGCC_C_LANGUAGE_STANDARD = gnu17;")
        appendLine("\t\t\t\tGCC_DYNAMIC_NO_PIC = NO;")
        appendLine("\t\t\t\tGCC_NO_COMMON_BLOCKS = YES;")
        appendLine("\t\t\t\tGCC_OPTIMIZATION_LEVEL = 0;")
        appendLine("\t\t\t\tGCC_PREPROCESSOR_DEFINITIONS = (")
        appendLine("\t\t\t\t\t\"DEBUG=1\",")
        appendLine("\t\t\t\t\t\"\${'$'}(inherited)\",")
        appendLine("\t\t\t\t);")
        appendLine("\t\t\t\tGCC_WARN_64_TO_32_BIT_CONVERSION = YES;")
        appendLine("\t\t\t\tGCC_WARN_ABOUT_RETURN_TYPE = YES_ERROR;")
        appendLine("\t\t\t\tGCC_WARN_UNDECLARED_SELECTOR = YES;")
        appendLine("\t\t\t\tGCC_WARN_UNINITIALIZED_AUTOS = YES_AGGRESSIVE;")
        appendLine("\t\t\t\tGCC_WARN_UNUSED_FUNCTION = YES;")
        appendLine("\t\t\t\tGCC_WARN_UNUSED_VARIABLE = YES;")
        appendLine("\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = $deploymentTarget;")
        appendLine("\t\t\t\tLOCALIZATION_PREFERS_STRING_CATALOGS = YES;")
        appendLine("\t\t\t\tMTL_ENABLE_DEBUG_INFO = INCLUDE_SOURCE;")
        appendLine("\t\t\t\tMTL_FAST_MATH = YES;")
        appendLine("\t\t\t\tONLY_ACTIVE_ARCH = YES;")
        appendLine("\t\t\t\tSDKROOT = iphoneos;")
        appendLine("\t\t\t\tSWIFT_ACTIVE_COMPILATION_CONDITIONS = \"DEBUG \${'$'}(inherited)\";")
        appendLine("\t\t\t\tSWIFT_OPTIMIZATION_LEVEL = \"-Onone\";")
        appendLine("\t\t\t};")
        appendLine("\t\t\tname = Debug;")
        appendLine("\t\t};")

        // Project Release
        appendLine("\t\t$projectReleaseConfigId /* Release */ = {")
        appendLine("\t\t\tisa = XCBuildConfiguration;")
        appendLine("\t\t\tbuildSettings = {")
        appendLine("\t\t\t\tALWAYS_SEARCH_USER_PATHS = NO;")
        appendLine("\t\t\t\tASYNCRONOUS_SYMBOL_EXPORT = YES;")
        appendLine("\t\t\t\tCLANG_ANALYZER_NONNULL = YES;")
        appendLine("\t\t\t\tCLANG_ANALYZER_NUMBER_OBJECT_CONVERSION = YES_AGGRESSIVE;")
        appendLine("\t\t\t\tCLANG_CXX_LANGUAGE_STANDARD = \"gnu++20\";")
        appendLine("\t\t\t\tCLANG_ENABLE_MODULES = YES;")
        appendLine("\t\t\t\tCLANG_ENABLE_OBJC_ARC = YES;")
        appendLine("\t\t\t\tCLANG_ENABLE_OBJC_WEAK = YES;")
        appendLine("\t\t\t\tCLANG_WARN_BLOCK_CAPTURE_AUTORELEASING = YES;")
        appendLine("\t\t\t\tCLANG_WARN_BOOL_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_COMMA = YES;")
        appendLine("\t\t\t\tCLANG_WARN_CONSTANT_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_DEPRECATED_OBJC_IMPLEMENTATIONS = YES;")
        appendLine("\t\t\t\tCLANG_WARN_DIRECT_OBJC_ISA_USAGE = YES_ERROR;")
        appendLine("\t\t\t\tCLANG_WARN_DOCUMENTATION_COMMENTS = YES;")
        appendLine("\t\t\t\tCLANG_WARN_EMPTY_BODY = YES;")
        appendLine("\t\t\t\tCLANG_WARN_ENUM_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_INFINITE_RECURSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_INT_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_NON_LITERAL_NULL_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_OBJC_IMPLICIT_RETAIN_SELF = YES;")
        appendLine("\t\t\t\tCLANG_WARN_OBJC_LITERAL_CONVERSION = YES;")
        appendLine("\t\t\t\tCLANG_WARN_OBJC_ROOT_CLASS = YES_ERROR;")
        appendLine("\t\t\t\tCLANG_WARN_QUOTED_INCLUDE_IN_FRAMEWORK_HEADER = YES;")
        appendLine("\t\t\t\tCLANG_WARN_RANGE_LOOP_ANALYSIS = YES;")
        appendLine("\t\t\t\tCLANG_WARN_STRICT_PROTOTYPES = YES;")
        appendLine("\t\t\t\tCLANG_WARN_SUSPICIOUS_MOVE = YES;")
        appendLine("\t\t\t\tCLANG_WARN_UNGUARDED_AVAILABILITY = YES_AGGRESSIVE;")
        appendLine("\t\t\t\tCLANG_WARN_UNREACHABLE_CODE = YES;")
        appendLine("\t\t\t\tCLANG_WARN___DUPLICATE_METHOD_MATCH = YES;")
        appendLine("\t\t\t\tCOPY_PHASE_STRIP = NO;")
        appendLine("\t\t\t\tDEBUG_INFORMATION_FORMAT = \"dwarf-with-dsym\";")
        appendLine("\t\t\t\tENABLE_NS_ASSERTIONS = NO;")
        appendLine("\t\t\t\tENABLE_STRICT_OBJC_MSGSEND = YES;")
        appendLine("\t\t\t\tENABLE_USER_SCRIPT_SANDBOXING = YES;")
        appendLine("\t\t\t\tGCC_C_LANGUAGE_STANDARD = gnu17;")
        appendLine("\t\t\t\tGCC_NO_COMMON_BLOCKS = YES;")
        appendLine("\t\t\t\tGCC_WARN_64_TO_32_BIT_CONVERSION = YES;")
        appendLine("\t\t\t\tGCC_WARN_ABOUT_RETURN_TYPE = YES_ERROR;")
        appendLine("\t\t\t\tGCC_WARN_UNDECLARED_SELECTOR = YES;")
        appendLine("\t\t\t\tGCC_WARN_UNINITIALIZED_AUTOS = YES_AGGRESSIVE;")
        appendLine("\t\t\t\tGCC_WARN_UNUSED_FUNCTION = YES;")
        appendLine("\t\t\t\tGCC_WARN_UNUSED_VARIABLE = YES;")
        appendLine("\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = $deploymentTarget;")
        appendLine("\t\t\t\tLOCALIZATION_PREFERS_STRING_CATALOGS = YES;")
        appendLine("\t\t\t\tMTL_ENABLE_DEBUG_INFO = NO;")
        appendLine("\t\t\t\tMTL_FAST_MATH = YES;")
        appendLine("\t\t\t\tSDKROOT = iphoneos;")
        appendLine("\t\t\t\tSWIFT_COMPILATION_MODE = wholemodule;")
        appendLine("\t\t\t\tVALIDATE_PRODUCT = YES;")
        appendLine("\t\t\t};")
        appendLine("\t\t\tname = Release;")
        appendLine("\t\t};")

        // Target Debug
        appendLine("\t\t$targetDebugConfigId /* Debug */ = {")
        appendLine("\t\t\tisa = XCBuildConfiguration;")
        appendLine("\t\t\tbuildSettings = {")
        appendLine("\t\t\t\tASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;")
        appendLine("\t\t\t\tASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;")
        appendLine("\t\t\t\tCODE_SIGN_STYLE = Automatic;")
        if (developmentTeam != null) {
            appendLine("\t\t\t\tDEVELOPMENT_TEAM = $developmentTeam;")
        }
        appendLine("\t\t\t\tINFOPLIST_FILE = \"$name/Info.plist\";")
        appendLine("\t\t\t\tINFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents = YES;")
        appendLine("\t\t\t\tINFOPLIST_KEY_UILaunchStoryboardName = LaunchScreen;")
        appendLine("\t\t\t\tINFOPLIST_KEY_UISupportedInterfaceOrientations_iPad = \"UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight\";")
        appendLine("\t\t\t\tINFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone = \"UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight\";")
        appendLine("\t\t\t\tLD_RUNPATH_SEARCH_PATHS = (")
        appendLine("\t\t\t\t\t\"\${'$'}(inherited)\",")
        appendLine("\t\t\t\t\t\"@executable_path/Frameworks\",")
        appendLine("\t\t\t\t);")
        appendLine("\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = \"$bundleId\";")
        appendLine("\t\t\t\tPRODUCT_NAME = \"\${'$'}(TARGET_NAME)\";")
        appendLine("\t\t\t\tSWIFT_EMIT_LOC_STRINGS = YES;")
        appendLine("\t\t\t\tSWIFT_VERSION = $swiftVersion;")
        appendLine("\t\t\t\tTARGETED_DEVICE_FAMILY = \"1,2\";")
        appendLine("\t\t\t};")
        appendLine("\t\t\tname = Debug;")
        appendLine("\t\t};")

        // Target Release
        appendLine("\t\t$targetReleaseConfigId /* Release */ = {")
        appendLine("\t\t\tisa = XCBuildConfiguration;")
        appendLine("\t\t\tbuildSettings = {")
        appendLine("\t\t\t\tASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;")
        appendLine("\t\t\t\tASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;")
        appendLine("\t\t\t\tCODE_SIGN_STYLE = Automatic;")
        if (developmentTeam != null) {
            appendLine("\t\t\t\tDEVELOPMENT_TEAM = $developmentTeam;")
        }
        appendLine("\t\t\t\tINFOPLIST_FILE = \"$name/Info.plist\";")
        appendLine("\t\t\t\tINFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents = YES;")
        appendLine("\t\t\t\tINFOPLIST_KEY_UILaunchStoryboardName = LaunchScreen;")
        appendLine("\t\t\t\tINFOPLIST_KEY_UISupportedInterfaceOrientations_iPad = \"UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight\";")
        appendLine("\t\t\t\tINFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone = \"UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight\";")
        appendLine("\t\t\t\tLD_RUNPATH_SEARCH_PATHS = (")
        appendLine("\t\t\t\t\t\"\${'$'}(inherited)\",")
        appendLine("\t\t\t\t\t\"@executable_path/Frameworks\",")
        appendLine("\t\t\t\t);")
        appendLine("\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = \"$bundleId\";")
        appendLine("\t\t\t\tPRODUCT_NAME = \"\${'$'}(TARGET_NAME)\";")
        appendLine("\t\t\t\tSWIFT_EMIT_LOC_STRINGS = YES;")
        appendLine("\t\t\t\tSWIFT_VERSION = $swiftVersion;")
        appendLine("\t\t\t\tTARGETED_DEVICE_FAMILY = \"1,2\";")
        appendLine("\t\t\t};")
        appendLine("\t\t\tname = Release;")
        appendLine("\t\t};")
        appendLine("/* End XCBuildConfiguration section */")
        appendLine()

        // XCConfigurationList section
        appendLine("/* Begin XCConfigurationList section */")
        appendLine("\t\t$projectConfigListId /* Build configuration list for PBXProject \"$name\" */ = {")
        appendLine("\t\t\tisa = XCConfigurationList;")
        appendLine("\t\t\tbuildConfigurations = (")
        appendLine("\t\t\t\t$projectDebugConfigId /* Debug */,")
        appendLine("\t\t\t\t$projectReleaseConfigId /* Release */,")
        appendLine("\t\t\t);")
        appendLine("\t\t\tdefaultConfigurationIsVisible = 0;")
        appendLine("\t\t\tdefaultConfigurationName = Release;")
        appendLine("\t\t};")
        appendLine("\t\t$targetConfigListId /* Build configuration list for PBXNativeTarget \"$name\" */ = {")
        appendLine("\t\t\tisa = XCConfigurationList;")
        appendLine("\t\t\tbuildConfigurations = (")
        appendLine("\t\t\t\t$targetDebugConfigId /* Debug */,")
        appendLine("\t\t\t\t$targetReleaseConfigId /* Release */,")
        appendLine("\t\t\t);")
        appendLine("\t\t\tdefaultConfigurationIsVisible = 0;")
        appendLine("\t\t\tdefaultConfigurationName = Release;")
        appendLine("\t\t};")
        appendLine("/* End XCConfigurationList section */")
        appendLine()

        appendLine("\t};")
        appendLine("\trootObject = $rootObjectId /* Project object */;")
        appendLine("}")
    }

    /**
     * Write the .xcodeproj to a directory.
     */
    fun writeTo(directory: File): File {
        val xcodeproj = directory.resolve("$name.xcodeproj")
        xcodeproj.mkdirs()

        val pbxproj = xcodeproj.resolve("project.pbxproj")
        pbxproj.writeText(generate())

        return xcodeproj
    }

    companion object {
        private var idCounter = 0

        private fun generateId(): String {
            // Xcode uses 24-character hex IDs
            val uuid = UUID.randomUUID().toString().replace("-", "").uppercase()
            return uuid.take(24)
        }

        /**
         * Create an Xcode project for a simple iOS app.
         */
        fun forApp(
            name: String,
            bundleId: String,
            appDir: File,
            deploymentTarget: String = "14.0"
        ): XcodeProject {
            val project = XcodeProject(
                name = name,
                bundleId = bundleId,
                deploymentTarget = deploymentTarget
            )

            // Add standard app files if they exist
            val swiftFiles = appDir.listFiles { f -> f.extension == "swift" } ?: emptyArray()
            for (file in swiftFiles) {
                project.addSwiftFile(file.name)
            }

            if (appDir.resolve("Info.plist").exists()) {
                project.addInfoPlist("Info.plist")
            }

            if (appDir.resolve("LaunchScreen.storyboard").exists()) {
                project.addStoryboard("LaunchScreen.storyboard")
            }

            if (appDir.resolve("Assets.xcassets").exists()) {
                project.addAssetCatalog("Assets.xcassets")
            }

            return project
        }
    }

    // Helper interface for object tracking
    private interface PBXObject
}
