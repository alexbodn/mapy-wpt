const regexStr = "(?i)\\\\b((?:https?://|www\\\\d{0,3}[.]|[a-z0-9.\\\\-]+[.][a-z]{2,4}/)(?:[^\\\\s()<>]+|\\\\((?:[^\\\\s()<>]+|\\\\([^\\\\s()<>]+\\\\))*\\\\))+(?:\\\\((?:[^\\\\s()<>]+|\\\\([^\\\\s()<>]+\\\\))*\\\\)|[^\\\\s`!()\\\\[\\\\]{};:'\".,<>?«»“”‘’]))";

// Let's test the regex using Kotlin's equivalent behavior
const sharedText = "https://mapy.com/s/recokokusu";
console.log(sharedText.match(new RegExp(regexStr.replace("(?i)", ""), "i")));
