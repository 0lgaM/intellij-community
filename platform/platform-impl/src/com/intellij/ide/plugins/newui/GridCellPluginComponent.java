// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins.newui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.plugins.*;
import com.intellij.openapi.wm.ex.ProgressIndicatorEx;
import com.intellij.ui.components.labels.LinkListener;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.ui.AbstractLayoutManager;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Alexander Lobas
 */
public class GridCellPluginComponent extends CellPluginComponent {
  private final MyPluginModel myPluginModel;
  private JLabel myLastUpdated;
  private JLabel myDownloads;
  private JLabel myRating;
  private final JButton myInstallButton = new InstallButton(false);
  private JComponent myLastComponent;
  private List<TagComponent> myTagComponents;
  private ProgressIndicatorEx myIndicator;

  public GridCellPluginComponent(@NotNull MyPluginModel pluginsModel,
                                 @NotNull IdeaPluginDescriptor plugin,
                                 @NotNull TagBuilder tagBuilder) {
    super(plugin);
    myPluginModel = pluginsModel;
    pluginsModel.addComponent(this);

    JPanel container = new NonOpaquePanel(new BorderLayout(JBUI.scale(10), 0));
    add(container);
    addIconComponent(container, BorderLayout.WEST);

    JPanel centerPanel = new NonOpaquePanel(new VerticalLayout(PluginManagerConfigurableNew.offset5(), JBUI.scale(180)));
    container.add(centerPanel);

    addNameComponent(centerPanel);
    addTags(centerPanel, tagBuilder);
    addDescriptionComponent(centerPanel, PluginManagerConfigurableNew.getShortDescription(myPlugin, false), new LineFunction(3, true));

    createMetricsPanel(centerPanel);

    addInstallButton();

    setOpaque(true);
    setBorder(JBUI.Borders.empty(10, 5));

    setLayout(new AbstractLayoutManager() {
      @Override
      public Dimension preferredLayoutSize(Container parent) {
        Dimension size = container.getPreferredSize();
        size.height += PluginManagerConfigurableNew.offset5();
        size.height += myLastComponent.getPreferredSize().height;
        JBInsets.addTo(size, parent.getInsets());
        return size;
      }

      @Override
      public void layoutContainer(Container parent) {
        Insets insets = parent.getInsets();
        Dimension size = container.getPreferredSize();
        Rectangle bounds = new Rectangle(insets.left, insets.top, size.width, size.height);
        container.setBounds(bounds);
        container.doLayout();

        Point location = centerPanel.getLocation();
        Dimension buttonSize = myLastComponent.getPreferredSize();
        Border border = myLastComponent.getBorder();
        int borderOffset = border == null ? 0 : border.getBorderInsets(myLastComponent).left;
        myLastComponent
          .setBounds(bounds.x + location.x - borderOffset, bounds.y + PluginManagerConfigurableNew.offset5() + bounds.height, Math.min(buttonSize.width, size.width),
                     buttonSize.height);
      }
    });

    updateIcon(false, false);
    setSelection(EventHandler.SelectionType.NONE);
  }

  private void createMetricsPanel(@NotNull JPanel centerPanel) {
    if (!(myPlugin instanceof PluginNode)) {
      return;
    }

    String downloads = PluginManagerConfigurableNew.getDownloads(myPlugin);
    String date = PluginManagerConfigurableNew.getLastUpdatedDate(myPlugin);
    String rating = PluginManagerConfigurableNew.getRating(myPlugin);

    if (downloads != null || date != null || rating != null) {
      JPanel panel = new NonOpaquePanel(new HorizontalLayout(JBUI.scale(7)));
      centerPanel.add(panel);

      if (date != null) {
        myLastUpdated = new JLabel(date, AllIcons.Plugins.Updated, SwingConstants.CENTER);
        myLastUpdated.setOpaque(false);
        panel.add(PluginManagerConfigurableNew.installTiny(myLastUpdated));
      }

      if (downloads != null) {
        myDownloads = new JLabel(downloads, AllIcons.Plugins.Downloads, SwingConstants.CENTER);
        myDownloads.setOpaque(false);
        panel.add(PluginManagerConfigurableNew.installTiny(myDownloads));
      }

      if (rating != null) {
        myRating = new JLabel(rating, AllIcons.Plugins.Rating, SwingConstants.CENTER);
        myRating.setOpaque(false);
        panel.add(PluginManagerConfigurableNew.installTiny(myRating));
      }
    }
  }

  private void addInstallButton() {
    if (InstalledPluginsState.getInstance().wasInstalled(myPlugin.getPluginId())) {
      RestartButton restartButton = new RestartButton(myPluginModel);
      restartButton.setFocusable(false);
      add(myLastComponent = restartButton);
      return;
    }

    myInstallButton.setFocusable(false);
    myInstallButton.addActionListener(e -> myPluginModel.installOrUpdatePlugin(myPlugin, true));
    myInstallButton.setEnabled(PluginManager.getPlugin(myPlugin.getPluginId()) == null);
    add(myLastComponent = myInstallButton);

    if (MyPluginModel.isInstallingOrUpdate(myPlugin)) {
      showProgress(false);
    }
  }

  public void showProgress() {
    showProgress(true);
  }

  private void showProgress(boolean repaint) {
    TwoLineProgressIndicator indicator = new TwoLineProgressIndicator();
    indicator.setCancelRunnable(() -> myPluginModel.finishInstall(myPlugin, false));
    myIndicator = indicator;

    myInstallButton.setVisible(false);
    add(myLastComponent = indicator.getComponent());
    doLayout();

    myPluginModel.addProgress(myPlugin, indicator);

    if (repaint) {
      fullRepaint();
    }
  }

  public void hideProgress(boolean success) {
    myIndicator = null;
    JComponent lastComponent = myLastComponent;
    if (success) {
      RestartButton restartButton = new RestartButton(myPluginModel);
      restartButton.setFocusable(false);
      add(myLastComponent = restartButton);
    }
    else {
      myLastComponent = myInstallButton;
      myInstallButton.setVisible(true);
    }
    remove(lastComponent);
    doLayout();
    fullRepaint();
  }

  private void addTags(@NotNull JPanel parent, @NotNull TagBuilder tagBuilder) {
    List<String> tags = PluginManagerConfigurableNew.getTags(myPlugin);
    if (tags.isEmpty()) {
      return;
    }

    NonOpaquePanel panel = new NonOpaquePanel(new HorizontalLayout(JBUI.scale(6)));
    parent.add(panel);

    myTagComponents = new ArrayList<>();

    for (String tag : tags) {
      TagComponent component = tagBuilder.createTagComponent(tag);
      panel.add(component);
      myTagComponents.add(component);
    }
  }

  @Override
  public void setListeners(@NotNull LinkListener<IdeaPluginDescriptor> listener,
                           @NotNull LinkListener<String> searchListener,
                           @NotNull EventHandler eventHandler) {
    super.setListeners(listener, searchListener, eventHandler);

    if (myDescription != null) {
      UIUtil.setCursor(myDescription, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

      myDescription.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent event) {
          if (SwingUtilities.isLeftMouseButton(event)) {
            listener.linkSelected(myName, myPlugin);
          }
        }
      });
      myDescription.addMouseListener(myHoverNameListener);
    }

    if (myTagComponents != null) {
      for (TagComponent component : myTagComponents) {
        //noinspection unchecked
        component.setListener(searchListener, SearchQueryParser.getTagQuery(component.getText()));
      }
      myTagComponents = null;
    }
  }

  @Override
  protected void updateColors(@NotNull Color grayedFg, @NotNull Color background) {
    super.updateColors(grayedFg, background);

    if (myLastUpdated != null) {
      myLastUpdated.setForeground(grayedFg);
    }
    if (myDownloads != null) {
      myDownloads.setForeground(grayedFg);
    }
    if (myRating != null) {
      myRating.setForeground(grayedFg);
    }
  }

  @Override
  public void close() {
    if (myIndicator != null) {
      myPluginModel.removeProgress(myPlugin, myIndicator);
      myIndicator = null;
    }
    myPluginModel.removeComponent(this);
  }
}