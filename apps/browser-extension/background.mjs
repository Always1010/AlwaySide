// Keep open() on the action event's original user gesture. No capture or request
// runs here: activeTab is granted by the toolbar action, not a panel button.
chrome.action.onClicked.addListener((tab) => {
  const showError = () => {
    void chrome.action.setBadgeText({ text: "!" }).catch(() => {});
    void chrome.action.setTitle({ title: "侧栏打开失败，请确认浏览器支持侧栏并再次点击" }).catch(() => {});
  };
  try {
    chrome.sidePanel.open({ windowId: tab.windowId }).then(() => {
      void chrome.action.setBadgeText({ text: "" }).catch(() => {});
      void chrome.action.setTitle({ title: "打开 AlwaySide，并允许读取当前网页" }).catch(() => {});
    }).catch(showError);
  } catch {
    showError();
  }
});
