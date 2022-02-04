# Huberman Labs eBook creator

This is a project which processes files downloaded from the YouTube
channel of the [Huberman Lab](https://hubermanlab.com/) podcast and 
converts them into Markdown. These Markdown files can then be processed
and turned into an epub eBook which can then be read offline.

I'm using a Mac, but anything Unixy will probably do.

Download the required files using [yt-dlp](https://github.com/yt-dlp/yt-dlp):

``` 
yt-dlp --write-sub --sub-lang en --sub-format srt --skip-download --write-description 'https://www.youtube.com/playlist?list=PLPNW_gerXa4Pc8S2qoUQc5e8Ir97RLuVW'
```

The project is written in Clojure, so you'll need to 
[install Clojure](https://clojure.org/guides/getting_started#_clojure_installer_and_cli_tools)
to run this. Once you have Clojure set up and you have downloaded the files as above:

```
clj -X huberman.core/process-files
```

This will produce a series of Markdown files like this:

```
001. How Your Nervous System Works & Changes.md
002. Master Your Sleep & Be More Alert When Awake.md
003. Using Science to Optimize Sleep, Learning & Metabolism.md
...etc etc...
```

If you'd like to convert the files into an e-book to read on an e-reader, you can do
this using [pandoc](https://pandoc.org/):

```
pandoc --metadata title="Huberman Lab" -o huberman.epub *\ *.md
```

That will output a file called `huberman.epub` with all the transcripts organised into
chapters and sections. If you want to read this on a Kindle, you can convert it using
either [Calibre](https://calibre-ebook.com/) or 
[Kindle Previewer](https://www.amazon.com/Kindle-Previewer/b?ie=UTF8&node=21381691011).
