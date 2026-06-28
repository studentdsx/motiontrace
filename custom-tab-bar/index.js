Component({
  data: {
    selected: 0,
    list: [
      {
        pagePath: '/pages/index/index',
        text: '今日'
      },
      {
        pagePath: '/pages/history/history',
        text: '历史'
      },
      {
        pagePath: '/pages/settings/settings',
        text: '设置'
      }
    ]
  },

  methods: {
    switchTab: function(event) {
      var index = event.currentTarget.dataset.index;
      var path = event.currentTarget.dataset.path;
      this.setData({ selected: index });
      wx.switchTab({ url: path });
    },

    setSelected: function(index) {
      this.setData({ selected: index });
    }
  }
});
