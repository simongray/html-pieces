// An app that sanitizes HTML with DOMPurify.
import DOMPurify from "dompurify";

console.log(DOMPurify.sanitize(document.body.innerHTML));
