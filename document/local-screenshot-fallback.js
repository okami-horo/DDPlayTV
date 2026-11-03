document.addEventListener('error', function (event) {
    const image = event.target;
    if (!(image instanceof HTMLImageElement)) return;

    const notice = document.createElement('p');
    notice.setAttribute('role', 'note');
    notice.textContent = '截图仅在本地归档，请恢复到原路径后查看：' + image.getAttribute('src');
    image.replaceWith(notice);
}, true);
