# Fadle

Name is temporary, but named in the hopes that this will be ***FATAL TO GRADLE***.

A build library, as opposed to a build system.  Instead of having an independent coding system with different conventions, this is simply a Maven library you can import and use to build your projects from vanilla Kotlin using tools such as [kscript](https://github.com/holgerbrandl/kscript), [skate](https://github.com/UnknownJoe796/skate), and even the upcoming standard Kotlin script runner.

This is a WIP, and is not available in Maven *yet*.  We'll get there.

## Why not Gradle/Kobalt?
- Gradle was built Groovy-first, and as such, its APIs are mostly untyped still
- Plugins - The plugin systems allow the plugin developer to change the meaning of certain tasks without letting the end user easily track what they.  It makes the execution path hard to follow.  I prefer that my build script code act and be built the same way as the code I run.
- More standard - Kotlin is more standard than either Gradle or Kobalt, and with tools such as [kscript](https://github.com/holgerbrandl/kscript), [skate](https://github.com/UnknownJoe796/skate), and even the upcoming standard Kotlin script runner, we can run single Kotlin files directly.  Instead of running some special scripting environment, we're now doing things the standard JVM/Kotlin way, and IntelliJ is optimized for it.  Editing build scripts is much more efficient.
- Environment-agnostic - Build tools written for plain Kotlin/Maven will also work in both Gradle and Kobalt without any changes.
- Size of API - The API size of Gradle is staggering; as such, learning how Gradle works takes way longer than it should.

## Principles
- Minimal - The fewest number of parts that get the job done is best.
- Clarity over conciseness - Short is good, understanding is better.  The build system should make it easy to understand what is going on and prevent mistakes.
- Project is API - There is no part of this project which isn't API.  Users are intended to be able to read everything here and use everything here, though they shouldn't have to.
    - If the code isn't good enough to be exposed to the public, rewrite it.
    - Use names that are as clear as possible so that users don't need to go look at your sources.
    - No plugins.  You can import other libraries which add functionality and use them, but there are no magical plugins which modify things outside of themselves.
- Orient it around Kotlin - users of Kotlin should be able to pick it up more quickly if it's truly focused on using Kotlin and its principles.
    - Use Kotlin Standard Library to the fullest possible
    - Simplify concepts back into standard Kotlin where possible
        - Use functions for tasks.  Gradle allowed for dynamic addition of functionality using this by adding `before{}` and `after{}`.  However, clarity is more important than conciseness in this project, so instead it is encouraged that one override a function.
- Use vendors' libraries directly where possible, exposing them to the user
    - If a vendor's library is too complicated, add a simple set of extensions, ideally no more than one file.
    - Avoid creating new types as much as possible
    - Example: Using `org.apache.maven:maven-model` for handling the structure of POM files.
    - Example: Using `org.jetbrains.kotlin:kotlin-compiler-embeddable` to compile Kotlin.
- Minimize DSLs/Configure with Lambda
    - Functions over lambda configuration
        - Arguments make what is required is clearer and compile-time safe
        - Default values are clearer
    - Extra interface over real objects is discouraged; see principle 1
    - DSLs can be good under very specific conditions, but these are more rare than common.
    
## Structure

Your project's definition file will be a single Kotlin file with `@DependsOn("com.ivieleague:fadle:<version>")` at the top.  It can be a `.kt` or a `.kts`, depending on what tool you're using to run it.  Skate runs `.kt`, KScript runs both `.kt` and `.kts`, and the Kotlin Script Runner uses `.kts`.

Your project is then defined as an `object` implementing interfaces with defaults, like this:

```kotlin
@DependsOn("com.ivieleague:fadle:<version>")

object MyProject: KotlinJVMModule, JvmRunnable {
    override val mainClass: String get() = "com.test.TestKt"
    override val root: File get() = File("temp")
    override val version: Version get() = Version(0, 0, 1)
    override val jvmJarLibraries: List<Library> get() = listOf(Kotlin.standardLibrary)
}
```

Now open that file within a REPL and you can do `MyProject.build()` or `MyProject.run()` or more!

There are several advantages to this kind of structure:

- Going to the definition of how something works is easy; just control-click whatever you're wondering about.
    - With everything directly linked, finding the execution path is much simpler.
- The different interfaces that define functions like `.build()` or `.run()` can require that another property be present
    - Their presence is validated at edit/compile time rather than run time, making it easier to use
    - IDEs will tell you what things are needed to get the project file working without searching through documentation
- The different interfaces can provide implementations of some properties
    - Example: `HasMavenInformation` implements `val jvmJarLibraries: List<Library>` for you by looking at the dependencies in your Maven model.
- Different interfaces can be used together easily.
- You can override certain pieces functionality without having to rewrite the whole thing.

More examples can be found in the unit tests, like this one specifically:
- [Plain Kotlin and Kotlin with Maven](src\test\kotlin\com\ivieleague\fadle\KotlinJVMModuleTest.kt)

## Planned Features 
- Integration with IntelliJ
    - Have a function on the project object (probably something like `prepare()`) that creates/updates the IntelliJ project
    - Make special plugin for IntelliJ unnecessary if at all possible
- Android
- Kotlin Multiplatform
- Kotlin JS - include NPM library
- Kotlin Native - include C Library OR Objective C Library
- Direct iOS support
    - Construct XCode Project with hooks back into this build system
    - Tasks which run the command line tools of XCode
- Automatic Semantic Versioning - Let the build tool analyze your code declarations and determine if it is fully compatible with previous versions of the library and change the version accordingly.
- Easy support for Maven library upload
- Easy support for including Maven libraries, typescript repositories, and native libraries via CInterop, perhaps with a declaration added to the POM file so child projects could also use it
- Meta-projects - If you have a set of projects that you wish to manage, you can use a meta-project to control them all from one place.  This is less of a feature and more ensuring that the system doesn't get in the way by using any globals.
- The Shattering - Eventually, this library should be split up into multiple artifacts to avoid downloading more dependencies than necessary.

## Want to help?
Check out the issue list on GitHub; we have plenty to do.  Understand, however, that your contributions need to be following the principles listed above.  If they don't, I'll give you feedback and help you get it in line, but it won't be merged until it does.  The cleanliness of this project is more important than features.

The other way you can help is kinda funny - the point of this system is to have build tools available in vanilla Kotlin.  You can contribute simply by creating other open-source libraries that do parts of the build process.