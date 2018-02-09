package no.skatteetaten.aurora.boober.utils


inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {

    var currentThrowable: Throwable? = null
    try {
        return block(this)
    } catch (throwable: Throwable) {
        currentThrowable = throwable
        throw throwable
    } finally {
        if (currentThrowable != null) {
            try {
                this.close()
            } catch (throwable: Throwable) {
                currentThrowable.addSuppressed(throwable)
            }
        } else {
            this.close()
        }
    }
}


abstract class Foo {
    open fun bar() = "string"
}

class Bar : Foo() {
    override fun bar() = "lkaj"
}
