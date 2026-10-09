// An app that renders HTML as Markdown with turndown.
import TurndownService from "turndown";

console.log(new TurndownService().turndown(document.body.innerHTML));
