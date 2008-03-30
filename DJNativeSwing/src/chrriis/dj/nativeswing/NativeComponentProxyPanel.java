/*
 * Christopher Deckers (chrriis@nextencia.net)
 * http://www.nextencia.net
 * 
 * See the file "readme.txt" for information on usage and redistribution of
 * this file, and for a DISCLAIMER OF ALL WARRANTIES.
 */
package chrriis.dj.nativeswing;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.MouseInfo;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyBoundsListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyVetoException;
import java.util.Arrays;

import javax.swing.JInternalFrame;
import javax.swing.JLayeredPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

import chrriis.common.UIUtils;
import chrriis.dj.nativeswing.NativeComponent.NativeComponentHolder;
import chrriis.dj.nativeswing.NativeComponentOptions.FiliationType;

import com.sun.jna.examples.WindowUtils;


/**
 * @author Christopher Deckers
 */
class NativeComponentProxyPanel extends NativeComponentProxy {

  private boolean isProxiedFiliation;

  protected NativeComponentProxyPanel(NativeComponent nativeComponent) {
    super(nativeComponent);
    isProxiedFiliation = nativeComponent.getOptions().getFiliationType() != FiliationType.DIRECT;
    addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        NativeComponentProxyPanel.this.nativeComponent.requestFocus();
      }
    });
  }

  private static class EmbeddedPanel extends Panel implements NativeComponentHolder {
    
    public EmbeddedPanel() {
      super(new BorderLayout(0, 0));
    }
    
    @Override
    public boolean contains(int x, int y) {
      return false;
    }
    
    @Override
    public boolean contains(Point p) {
      return false;
    }
    
  }
  
  private HierarchyBoundsListener hierarchyBoundsListener = new HierarchyBoundsListener() {
    public void ancestorMoved(HierarchyEvent e) {
      Component component = e.getChanged();
      if(component instanceof Window) {
        return;
      }
      adjustPeerBounds();
    }
    public void ancestorResized(HierarchyEvent e) {
      adjustPeerBounds();
    }
  };

  private MouseAdapter mouseListener = new MouseAdapter() {
    @Override
    public void mousePressed(MouseEvent e) {
      adjustFocus();
    }
    protected void adjustFocus() {
      for(Component parent = NativeComponentProxyPanel.this; parent != null && !(parent instanceof Window); parent = parent.getParent()) {
        if(parent instanceof JInternalFrame) {
          Window windowAncestor = SwingUtilities.getWindowAncestor(NativeComponentProxyPanel.this);
          if(windowAncestor != null) {
            boolean focusableWindowState = windowAncestor.getFocusableWindowState();
            windowAncestor.setFocusableWindowState(false);
            try {
              ((JInternalFrame)parent).setSelected(true);
            } catch (PropertyVetoException e1) {
            }
            windowAncestor.setFocusableWindowState(focusableWindowState);
          }
          break;
        }
      }
    }
  };
  
  private EmbeddedPanel panel;
  
  @Override
  protected Component createPeer() {
    panel = new EmbeddedPanel();
    panel.add(nativeComponent, BorderLayout.CENTER);
    return panel;
  }
  
  @Override
  protected void connectPeer() {
    addHierarchyBoundsListener(hierarchyBoundsListener);
    nativeComponent.addMouseListener(mouseListener);
  }
  
  @Override
  protected void disconnectPeer() {
    removeHierarchyBoundsListener(hierarchyBoundsListener);
    nativeComponent.removeMouseListener(mouseListener);
  }
  
  @Override
  protected void addPeer() {
    Window windowAncestor = SwingUtilities.getWindowAncestor(this);
    if(!(windowAncestor instanceof RootPaneContainer)) {
      throw new IllegalStateException("The window ancestor must be a root pane container!");
    }
    if(isProxiedFiliation) {
      JLayeredPane layeredPane = ((RootPaneContainer)windowAncestor).getLayeredPane();
      layeredPane.setLayer(panel, Integer.MIN_VALUE);
      layeredPane.add(panel);
    } else {
      add(panel, BorderLayout.CENTER);
    }
  }
  
  @Override
  protected void destroyPeer() {
    if(panel == null) {
      return;
    }
    Container parent = panel.getParent();
    if(parent != null) {
      parent.remove(panel);
      parent.invalidate();
      parent.validate();
      parent.repaint();
    }
    panel = null;
  }
  
  private volatile boolean isInvoking;
  
  protected void adjustPeerShape() {
    if(isInvoking) {
      return;
    }
    isInvoking = true;
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        isInvoking = false;
        adjustPeerShape_();
      }
    });
  }
  
  @Override
  protected void adjustPeerBounds() {
    if(!isProxiedFiliation) {
      return;
    }
    super.adjustPeerBounds();
  }
  
  private Rectangle[] lastArea = new Rectangle[] {new Rectangle(getSize())};
  
  protected Rectangle[] getPeerShapeArea() {
    return lastArea;
  }
  
  protected void adjustPeerShape_() {
    if(panel == null) {
      return;
    }
    Rectangle[] area = computePeerShapeArea();
    if(Arrays.equals(lastArea, area)) {
      return;
    }
    lastArea = area;
    if(area.length == 0) {
      if(!isCapturing) {
        panel.setVisible(false);
      }
    } else {
      if(!panel.isVisible()) {
        panel.setVisible(true);
      }
      if(!isCapturing) {
        WindowUtils.setComponentMask(panel, area);
      } else {
        updateCapturingComponentBounds();
      }
//      nativeComponent.repaintNativeControl();
    }
  }

  private boolean isCapturing;
  private Canvas capturingComponent;
  private static Robot robot;
  
  @Override
  void startCapture() {
    if(!SwingUtilities.isEventDispatchThread()) {
      try {
        SwingUtilities.invokeAndWait(new Runnable() {
          public void run() {
            startCapture();
          }
        });
      } catch(Throwable e) {
        throw new RuntimeException(e.getCause());
      }
      return;
    }
    if(!isVisibilityConstrained || panel == null) {
      return;
    }
    isCapturing = true;
    Container parent = panel.getParent();
    Window windowAncestor = SwingUtilities.getWindowAncestor(panel);
    Rectangle[] subtractingArea = new Rectangle[] {new Rectangle(panel.getWidth(), panel.getHeight())};
    subtractingArea = UIUtils.subtract(subtractingArea, lastArea);
    final Rectangle imageBounds = new Rectangle();
    if(subtractingArea.length > 0) {
      imageBounds.setBounds(subtractingArea[0]);
      for(int i=1; i<subtractingArea.length; i++) {
        Rectangle.union(imageBounds, subtractingArea[i], imageBounds);
      }
    }
    Point panelLocation = panel.getLocation();
    SwingUtilities.convertPointToScreen(panelLocation, parent);
    Rectangle r = new Rectangle(imageBounds.x + panelLocation.x, imageBounds.y + panelLocation.y, imageBounds.width, imageBounds.height);
    r = r.intersection(windowAncestor.getBounds());
    try {
      boolean isValidBounds = r.width > 0 && r.height > 0;
      final BufferedImage image;
      if(isValidBounds) {
        if(robot == null) {
          robot = new Robot();
        }
        image = robot.createScreenCapture(r);
        Point location = r.getLocation();
        SwingUtilities.convertPointFromScreen(location, parent);
        r.setLocation(location);
      } else {
        image = null;
      }
      if(isValidBounds) {
        capturingComponent = new Canvas() {
          @Override
          public void paint(Graphics g) {
            g.drawImage(image, 0, 0, this);
          }
          @Override
          public boolean contains(int x, int y) {
            return false;
          }
        };
        Point mouseLocation = MouseInfo.getPointerInfo().getLocation();
        SwingUtilities.convertPointFromScreen(mouseLocation, windowAncestor);
        Component hoveredComponent = windowAncestor.findComponentAt(mouseLocation);
        if(hoveredComponent != null) {
          capturingComponent.setCursor(hoveredComponent.getCursor());
        }
        capturingComponent.setBounds(r);
        capturingComponent.setVisible(false);
        parent.add(capturingComponent, 0);
        updateCapturingComponentBounds();
      }
      WindowUtils.setComponentMask(panel, (Rectangle[])null);
      if(!panel.isVisible()) {
        panel.setVisible(true);
      }
    } catch(Exception e) {
      isCapturing = false;
      e.printStackTrace();
      return;
    }
  }
  
  private void updateCapturingComponentBounds() {
    if(capturingComponent == null) {
      return;
    }
    Point l1 = capturingComponent.getLocation();
    Point l2 = panel.getLocation();
    int offsetX = l2.x - l1.x;
    int offsetY = l2.y - l1.y;
    Rectangle[] subtractingArea = new Rectangle[lastArea.length];
    for(int i=0; i<subtractingArea.length; i++) {
      Rectangle r = (Rectangle)lastArea[i].clone();
      r.translate(offsetX, offsetY);
      subtractingArea[i] = r;
    }
    Rectangle[] area = new Rectangle[] {new Rectangle(capturingComponent.getSize())};
    area = UIUtils.subtract(area, subtractingArea);
    if(area.length != 0) {
      WindowUtils.setComponentMask(capturingComponent, area);
      if(!capturingComponent.isVisible()) {
        capturingComponent.setVisible(true);
      }
    } else {
      capturingComponent.setVisible(false);
    }
  }
  
  @Override
  void stopCapture() {
    if(!SwingUtilities.isEventDispatchThread()) {
      try {
        SwingUtilities.invokeAndWait(new Runnable() {
          public void run() {
            stopCapture();
          }
        });
      } catch(Exception e) {
        e.printStackTrace();
      }
      return;
    }
    if(!isCapturing) {
      return;
    }
    if(lastArea.length != 0) {
      WindowUtils.setComponentMask(panel, lastArea);
      if(!panel.isVisible()) {
        panel.setVisible(true);
      }
    } else {
      panel.setVisible(false);
    }
    isCapturing = false;
    if(capturingComponent != null) {
      Container parent = capturingComponent.getParent();
      parent.remove(capturingComponent);
      capturingComponent = null;
    }
  }
  
}
