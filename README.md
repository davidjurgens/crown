# CROWN: the Community-enRiched Open WordNet

CROWN is a semantic network and ontology built on top of
[WordNet](http://wordnet.princeton.edu/wordnet/) and is directly compatible with
all WordNet-based software libraries and algorithms.  CROWN is built using
state-of-the-art machine learning to automatically enrich WordNet's existing
network with new lemmas and senses from resources such as
[Wiktionary](https://en.wiktionary.org).

This repository hosts the source code for building CROWN from scratch, all
documentation for the project, and the issue tracker for bugs and feature
requests.

# Why use CROWN?

  * Significantly-expanded vocabulary that includes many missing terms in
    WordNet --especially slang, idioms, and domain-specific terminology
  * Seamless compatibility as a drop in replacement for WordNet
  * Regularly updated with new content


# Quick Installation

CROWN is distributed as stand-alone dictionaries in the WordNet format, just
like WordNet 3.1, from our [Downloads](http://cs.mcgill.ca/~jurgens/crown/)
site.  See our [Releases](https://github.com/davidjurgens/crown/wiki/Releases)
page for full details.

If you use WordNet on the command line, installation is easy!  The
software provided with WordNet lets you seamlessly change the dictionary
directory used by the software with the `WNSEARCHDIR` environment
variable. Simply download the CROWN data release archive and unpack it. This
should create a directory called dict containing CROWN. Then set your
`WNSEARCHDIR` environment variable to the location of this directory, e.g.,

    export WNSEARCHDIR=/path/to/crown/dict

For a more permanent installation, simply replace the WordNet dict directory in
the directory where WordNet is installed (e.g., `/opt/local/share/WordNet-3.0/`)
with the CROWN dict.

If you use libraries to interface with WordNet, CROWN can be used by providing
the path to the CROWN dict directory instead of the usual WordNet directory. See
the WordNet page for [Related
Projects](http://wordnet.princeton.edu/wordnet/related-projects/) for library
options in your language of choice.

# Documentation

See our [project page](https://github.com/davidjurgens/crown/wiki/Home) for full details of the project.  The
[Installation](https://github.com/davidjurgens/crown/wiki/Installation) page has additional for detailed instructions on how to install
CROWN on systems and how to integrate the resource with commonly-used software
libraries.  Also, see our [Frequently Asked Questions](https://github.com/davidjurgens/crown/wiki/Frequently-Asked-Questions) for additional details
documentation.

# Credits

  * [David Jurgens](http://cs.mcgill.ca/~jurgens), McGill University
  * [Mohammad Taher Pilehvar](http://www.pilevar.com/taher/), Sapienza University of Rome

CROWN is also not possible without the extensive effort by the [WordNet
team](http://wordnet.princeton.edu/wordnet/about-wordnet/) in creating the
resource and the Wiktionary community in creating and maintaining a
community-constructed dictionary.

# Contact

For general questions or discussion, please get in touch with us on our [Google
group page](https://groups.google.com/d/forum/crown-users) or by email at
crown-users@googlegroups.com.

If you have discovered a bug in the build software or want to report an error in
the CROWN data, please create a new
[Issue](https://github.com/davidjurgens/crown/issues) on our github page.
