package no.skatteetaten.aurora.boober.controller.security

import kotlinx.coroutines.ThreadContextElement
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class SpringSecurityThreadContextElement(
    val securityContext: SecurityContext = SecurityContextHolder.getContext()
) : ThreadContextElement<SecurityContext>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpringSecurityThreadContextElement>

    /** @suppress */
    override fun updateThreadContext(context: CoroutineContext): SecurityContext {
        val oldState = SecurityContextHolder.getContext()
        SecurityContextHolder.setContext(securityContext)
        return oldState
    }

    /** @suppress */
    override fun restoreThreadContext(context: CoroutineContext, oldState: SecurityContext) {
        SecurityContextHolder.setContext(oldState)
    }
}
