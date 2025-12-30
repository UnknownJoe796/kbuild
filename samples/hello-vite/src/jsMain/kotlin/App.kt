import kotlinx.browser.document
import kotlinx.browser.window

/**
 * A simple Kotlin/JS web application demonstrating Vite integration.
 */
object App {
    private var clickCount = 0

    /**
     * Initialize the application.
     * Called from JavaScript when the page loads.
     */
    @JsExport
    fun main() {
        console.log("Hello from Kotlin/JS!")
        setupUI()
    }

    /**
     * Set up the UI elements.
     */
    private fun setupUI() {
        val root = document.getElementById("root")
        if (root != null) {
            root.innerHTML = """
                <div class="app">
                    <h1>🚀 Hello Vite + Kotlin!</h1>
                    <p>This app is built with:</p>
                    <ul>
                        <li>Kotlin/JS for the logic</li>
                        <li>Vite for dev server & bundling</li>
                        <li>Hot Module Replacement for fast updates</li>
                    </ul>
                    <div class="card">
                        <button id="counter-btn">Click count: $clickCount</button>
                    </div>
                    <p class="hint">Edit <code>src/jsMain/kotlin/App.kt</code> and save to see HMR in action</p>
                </div>
            """.trimIndent()

            // Set up click handler
            val button = document.getElementById("counter-btn")
            button?.addEventListener("click", { incrementCounter() })
        }
    }

    /**
     * Increment the counter and update the UI.
     */
    private fun incrementCounter() {
        clickCount++
        val button = document.getElementById("counter-btn")
        button?.textContent = "Click count: $clickCount"
        console.log("Counter incremented to $clickCount")
    }

    /**
     * Get the current greeting message.
     */
    @JsExport
    fun getGreeting(): String {
        return "Hello from Kotlin! Click count: $clickCount"
    }

    /**
     * Reset the counter.
     */
    @JsExport
    fun reset() {
        clickCount = 0
        val button = document.getElementById("counter-btn")
        button?.textContent = "Click count: $clickCount"
    }
}

fun main() {
    App.main()
}
