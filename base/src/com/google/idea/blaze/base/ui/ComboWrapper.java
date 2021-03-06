/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.ui;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ListCellRendererWrapper;
import java.awt.event.ActionListener;
import java.util.Collection;
import javax.swing.DefaultComboBoxModel;
import org.jetbrains.annotations.NotNull;

/**
 * A simple wrapper for IDEA's {@link ComboBox} class adding type safety for the methods we commonly
 * use.
 */
public final class ComboWrapper<T> {
  @NotNull private final ComboBox combo;

  public static <T> ComboWrapper<T> create() {
    return new ComboWrapper<T>();
  }

  private ComboWrapper() {
    combo = new ComboBox();
  }

  public void setItems(@NotNull Collection<T> values) {
    combo.setModel(new DefaultComboBoxModel(values.toArray()));
  }

  public void setSelectedItem(T value) {
    combo.setSelectedItem(value);
  }

  public T getSelectedItem() {
    return (T) combo.getSelectedItem();
  }

  public void addActionListener(@NotNull ActionListener listener) {
    combo.addActionListener(listener);
  }

  public void setRenderer(ListCellRendererWrapper<T> renderer) {
    combo.setRenderer(renderer);
  }

  @NotNull
  public ComboBox getCombo() {
    return combo;
  }
}
