let index = 0;
const slides = document.querySelectorAll('.slideshow .slide');
if (slides.length > 0) {
  slides[0].style.display = 'block';
  setInterval(() => {
    slides[index].style.display = 'none';
    index = (index + 1) % slides.length;
    slides[index].style.display = 'block';
  }, 4000);
}
