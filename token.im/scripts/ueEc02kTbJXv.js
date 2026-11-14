var toArray = function (obj) {
  return Array.prototype.slice.call(obj)
}

  ; (function () {
    document.addEventListener('DOMContentLoaded', function () {
      function throttle(fn, threshhold) {
        var last
        var timer
        threshhold || (threshhold = 250)

        return function () {
          var context = this
          var args = arguments
          var now = +new Date()

          if (last && now < last + threshhold) {
            clearTimeout(timer)
            timer = setTimeout(function () {
              last = now
              fn.apply(context, args)
            }, threshhold)
          } else {
            last = now
            fn.apply(context, args)
          }
        }
      }

      // Scroll window, active navbar bottom border
      var space = document.getElementById('space')
      if (space) {
        window.onscroll = throttle(function () {
          window.scrollY ? space.classList.add('scrolled') : space.classList.remove('scrolled')
        }, 200)
      }

      // Select navbar
      var navbarLinks = toArray(document.querySelectorAll('.navbar-link'))
      var currentURL = window.location.href
      if (navbarLinks) {
        navbarLinks.forEach(function (item) {
          if (currentURL.indexOf(item.href) !== -1) {
            item.classList.add('active')
          } else {
            item.classList.remove('acitve')
          }
        })
      }

      // Click navbar mobile menu
      var navbarMobileContainer = document.getElementById('navbar-mobile-container')
      var navbarMobileMenuBtn = document.getElementById('navbar-mobile-menu-btn')
      if (navbarMobileContainer && navbarMobileMenuBtn) {
        navbarMobileMenuBtn.onclick = function () {
          var isActive = navbarMobileContainer.classList.contains('active')
          if (isActive) {
            navbarMobileMenuBtn.classList.remove('is-active')
            navbarMobileContainer.classList.remove('active')
          } else {
            navbarMobileMenuBtn.classList.add('is-active')
            navbarMobileContainer.classList.add('active')
          }
        }
      }
    })
  })()

  ; (function () {
    function getUA() {
      return window.navigator.userAgent.toLowerCase()
    }

    function isWeixinBrowser() {
      return /micromessenger/.test(getUA())
    }

    function isAndroid() {
      return getUA().indexOf('android') !== -1
    }

    function isIOS() {
      return /ipad|iphone|ipod/.test(getUA())
    }

    function showHideDownloadItemsHelper(domArr, addClass, removeClass1, removeClass2) {
      domArr.forEach(function (dom) {
        addClass && dom.classList.add(addClass)
        removeClass1 && dom.classList.remove(removeClass1)
        removeClass2 && dom.classList.remove(removeClass2)
      })
    }

    function trackDownload() {
      var downloadBtns = toArray(document.querySelectorAll('.js_download'))
      downloadBtns.forEach(function (item) {
        item.onclick = function (e) {
          // download event tracking
          var { source, link } = this.dataset;
          var track = function (category) {
            ga && ga('send', 'event', 'downloads', 'click', category, 0)
          }

          if (!!link) {
            track(`${source}: ${link}`)
            return
          }

          track(source)
        }
      })
    }

    function trackHelpScout() {
      var helpScoutBlock = document.querySelector("#beacon-container .BeaconFabButtonFrame iframe")

      helpScoutBlock.contentDocument.onclick = function () {
        ga && ga('send', 'event', 'help_scout', 'click', window.location.pathname, 0)
      }
    }

    function addGATrack() {
      trackDownload()

      if (isWeixinBrowser()) {
        wechatBg.onclick = function () {
          wechatBg.classList.remove('active')
        }
      }
    }

    addGATrack();

    window.Beacon('on', 'ready', trackHelpScout)
    document.addEventListener('DOMContentLoaded', addGATrack);
  })();
