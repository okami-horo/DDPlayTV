package com.okamihoro.ddplaytv.ui.shell

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.xyoye.common_component.extension.hideFragment
import com.xyoye.common_component.extension.showFragment

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

        previousFragment?.also {
            fragmentManager.hideFragment(it)
        }

        val fragment = fragmentManager.findFragmentByTag(tag)
        if (fragment == null) {
            fragmentProvider(fragmentPath)?.also {
                addFragment(it, tag)
                previousFragment = it
                currentTag = tag
            }
            return
        }

        fragmentManager.showFragment(fragment)
        previousFragment = fragment
        currentTag = tag
    }

    private fun addFragment(
        fragment: Fragment,
        tag: String
    ) {
        fragmentManager
            .beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .add(containerId, fragment, tag)
            .commit()
    }
}
