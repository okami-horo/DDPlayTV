package com.okamihoro.ddplaytv.ui.shell

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle

class ShellFragmentSwitcher(
    private val fragmentManager: FragmentManager,
    private val containerId: Int,
    private val fragmentProvider: (String) -> Fragment?
) {
    private var currentTag: String? = null
    private var previousFragment: Fragment? = null

    fun currentFragment(): Fragment? = previousFragment

    fun switchFragment(
        tag: String,
        fragmentPath: String
    ) {
        if (tag == currentTag) {
            return
        }

        val fragment = fragmentManager.findFragmentByTag(tag) ?: fragmentProvider(fragmentPath) ?: return
        val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
        fragmentManager.fragments.filter { it.id == containerId && it !== fragment }.forEach {
            transaction.hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED)
        }
        if (fragment.isAdded) transaction.show(fragment) else transaction.add(containerId, fragment, tag)
        transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED).commitNow()
        previousFragment = fragment
        currentTag = tag
    }
}
