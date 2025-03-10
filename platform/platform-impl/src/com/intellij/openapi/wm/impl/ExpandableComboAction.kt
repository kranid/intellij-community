// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Key
import javax.swing.Icon
import javax.swing.JComponent

abstract class ExpandableComboAction : AnAction(), CustomComponentAction {

  companion object {
    @JvmField val LEFT_ICONS_KEY = Key.create<List<Icon>>("leftIcons")
    @JvmField val RIGHT_ICONS_KEY = Key.create<List<Icon>>("rightIcons")
  }

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    val model = MyPopupModel()
    model.addActionListener { actionEvent ->
      val start = System.nanoTime()
      val combo = (actionEvent.source as? ToolbarComboButton) ?: return@addActionListener
      val dataContext = DataManager.getInstance().getDataContext(combo)
      val anActionEvent = AnActionEvent.createFromDataContext(place, presentation, dataContext)
      val popup = createPopup(anActionEvent) ?: return@addActionListener
      popup.addListener(object : JBPopupListener {
        override fun beforeShown(event: LightweightWindowEvent) {
          model.isPopupShown = true
        }

        override fun onClosed(event: LightweightWindowEvent) {
          model.isPopupShown = false
        }
      })
      Utils.showPopupElapsedMillisIfConfigured(start, popup.content)
      popup.showUnderneathOf(combo)
    }
    return createToolbarComboButton(model)
  }

  protected open fun createToolbarComboButton(model: ToolbarComboButtonModel): ToolbarComboButton {
    return ToolbarComboButton(model)
  }

  abstract fun createPopup(event: AnActionEvent): JBPopup?

  override fun actionPerformed(e: AnActionEvent) {
    e.project?.let { createPopup(e)?.showCenteredInCurrentWindow(it) }
  }

  private class MyPopupModel : DefaultToolbarComboButtonModel() {
    var isPopupShown: Boolean = false

    override fun isSelected(): Boolean {
      return super.isSelected() || isPopupShown
    }
  }
}
