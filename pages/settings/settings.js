var trackStore = require('../../utils/trackStore');

Page({
  onShow: function() {
    if (this.getTabBar && this.getTabBar()) {
      this.getTabBar().setSelected(2);
    }
  },

  clearData: function() {
    wx.showModal({
      title: '清空数据',
      content: '确认清空本机保存的所有轨迹和打卡记录吗？此操作无法撤销。',
      confirmText: '清空',
      confirmColor: '#b94f44',
      success: function(res) {
        if (!res.confirm) return;
        trackStore.clearAll();
        wx.showToast({
          title: '已清空',
          icon: 'success'
        });
      }
    });
  }
});
