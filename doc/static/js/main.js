(function () {
  "use strict";

  const hamburger = document.getElementById("hamburger");
  const sidebar = document.getElementById("sidebar");
  const overlay = document.getElementById("sidebar-overlay");

  function openSidebar() {
    sidebar.classList.add("open");
    overlay.classList.add("visible");
    hamburger.classList.add("open");
    document.body.style.overflow = "hidden";
  }

  function closeSidebar() {
    sidebar.classList.remove("open");
    overlay.classList.remove("visible");
    hamburger.classList.remove("open");
    document.body.style.overflow = "";
  }

  if (hamburger) {
    hamburger.addEventListener("click", function () {
      sidebar.classList.contains("open") ? closeSidebar() : openSidebar();
    });
  }

  if (overlay) {
    overlay.addEventListener("click", closeSidebar);
  }

  const sidebarLinks = document.querySelectorAll('#sidebar a[href^="#"]');
  sidebarLinks.forEach(function (link) {
    link.addEventListener("click", function () {
      if (window.innerWidth <= 768) closeSidebar();
    });
  });

  const progressBar = document.getElementById("progress-bar");

  function updateProgress() {
    const scrollTop = window.scrollY;
    const docHeight =
      document.documentElement.scrollHeight - window.innerHeight;
    const pct = docHeight > 0 ? (scrollTop / docHeight) * 100 : 0;
    if (progressBar) progressBar.style.width = pct + "%";
  }

  const sections = document.querySelectorAll("section[id], div[id].section");
  const allSidebarLinks = document.querySelectorAll('#sidebar a[href^="#"]');

  function updateActiveLink() {
    let current = "";
    sections.forEach(function (sec) {
      const rect = sec.getBoundingClientRect();
      if (rect.top <= 120) current = sec.getAttribute("id");
    });
    allSidebarLinks.forEach(function (link) {
      const href = link.getAttribute("href").slice(1);
      link.classList.toggle("active", href === current);
    });
  }

  window.addEventListener(
    "scroll",
    function () {
      updateProgress();
      updateActiveLink();
    },
    { passive: true },
  );

  updateProgress();
  updateActiveLink();

  function addCopyButtons() {
    document.querySelectorAll("pre").forEach(function (pre) {
      if (pre.querySelector(".copy-btn")) return;
      const btn = document.createElement("button");
      btn.className = "copy-btn";
      btn.textContent = "Copy";
      btn.addEventListener("click", function () {
        const code = pre.querySelector("code");
        const text = code ? code.textContent : pre.textContent;
        navigator.clipboard
          .writeText(text)
          .then(function () {
            btn.textContent = "Copied";
            btn.classList.add("copied");
            setTimeout(function () {
              btn.textContent = "Copy";
              btn.classList.remove("copied");
            }, 1800);
          })
          .catch(function () {
            btn.textContent = "Error";
            setTimeout(function () {
              btn.textContent = "Copy";
            }, 1800);
          });
      });
      pre.style.position = "relative";
      pre.appendChild(btn);
    });
  }

  addCopyButtons();

  const tocToggle = document.querySelector(".toc-toggle");
  const tocBody = document.querySelector(".toc-body");

  if (tocToggle && tocBody) {
    tocToggle.addEventListener("click", function () {
      const open = tocBody.classList.toggle("open");
      tocToggle.querySelector(".toc-arrow").textContent = open ? "▲" : "▼";
    });
    tocBody.querySelectorAll("a").forEach(function (a) {
      a.addEventListener("click", function () {
        tocBody.classList.remove("open");
        tocToggle.querySelector(".toc-arrow").textContent = "▼";
      });
    });
  }

  const topNav = document.querySelectorAll('#top-bar nav a[href^="#"]');
  topNav.forEach(function (link) {
    link.addEventListener("click", function () {
      topNav.forEach(function (l) {
        l.classList.remove("active");
      });
      link.classList.add("active");
    });
  });
})();
