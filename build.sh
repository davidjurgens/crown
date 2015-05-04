#!/bin/bash


# Check for grind
hash grind 2>/dev/null || { echo >&2 "WN Grind is required but it's not installed.  Aborting."; exit 1; }

# Check for WordNet
WORDNET_DIR=WordNet-3.0
if [ ! -d WordNet-3.0 ] ; then
    echo 'WordNet 3.0 not found in the local directory; downloading ...'
    wget http://wordnetcode.princeton.edu/3.0/WordNet-3.0.tar.gz
    tar xzf WordNet-3.0.tar.gz
    rm WordNet-3.0.tar.gz
    # For some reason WordNet does not come with write permissions on some of
    # its files, which interferes with us copying and then overwriting them.
    chmod u+w WordNet-3.0/dict/*
fi

# Check for WordNet's lexicographer files
LEXFILE_DIR=lexicographer-files
if [ ! -d $LEXFILE_DIR ] ; then
    mkdir $LEXFILE_DIR
    echo 'Lexicographer files not found in the local directory; downloading ...'
    wget http://wordnetcode.princeton.edu/tools/grind/WNgrind-3.0.tar.gz
    tar xzf WNgrind-3.0.tar.gz
    mv WNgrind-3.0/dict/dbfiles $LEXFILE_DIR
    rm WNgrind-3.0.tar.gz
    rm -rf WNgrind-3.0
fi

WORKING_FILE_DIR=working-files

# We'll use this to store the temporary files
if [ ! -d "$WORKING_FILE_DIR" ] ; then
    mkdir $WORKING_FILE_DIR
fi


DATA_DIR=data

# We'll use this to store the temporary files
if [ ! -d "$DATA_DIR" ] ; then
    mkdir $DATA_DIR
fi


CROWN_DICT_DIR=crown-dict

# Check for the default output location
if [ ! -d "$CROWN_DICT_DIR" ] ; then
    mkdir $CROWN_DICT_DIR
fi

# Check for some form of input
INPUT_ARGS=""

DEFAULT_PREPROCESSED_INPUT_FILE=data/wiktionary.preprocessed.json
DEFAULT_UKP_INPUT_DIR=data/wiktionary-ukp-dir
DEFAULT_WIKTIONARY_FILE=data/enwiktionary-latest-pages-articles.xml

if [ -e "$DEFAULT_PREPROCESSED_INPUT_FILE" ] ; then
    INPUT_ARGS="-p $DEFAULT_PREPROCESSED_INPUT_FILE"
elif [ -d "$DEFAULT_UKP_INPUT_DIR" ] ; then
    INPUT_ARGS="-u $DEFAULT_UKP_INPUT_DIR -P $DEFAULT_PREPROCESSED_INPUT_FILE"
elif [ -e "$DEFAULT_WIKTIONARY_FILE" ] ; then
    INPUT_ARGS="-w $DEFAULT_WIKTIONARY_FILE  -P $DEFAULT_PREPROCESSED_INPUT_FILE"
else
    pushd $WORKING_FILE_DIR
    echo "No wiktionary data seems to be locally present in any format; downloading latest dump..."
    wget http://dumps.wikimedia.org/enwiktionary/latest/enwiktionary-latest-pages-articles.xml.bz2
    bunzip2 enwiktionary-latest-pages-articles.xml.bz2
    popd
    INPUT_ARGS="-w $DEFAULT_WIKTIONARY_FILE -P $DEFAULT_PREPROCESSED_INPUT_FILE"
fi

echo "Compiling the CROWN Build"
mvn package -Dmaven.javadoc.skip=true >/dev/null

java -jar target/crown-2.0.0-jar-with-dependencies.jar $INPUT_ARGS -T $WORKING_FILE_DIR $WORDNET_DIR $LEXFILE_DIR $CROWN_DICT_DIR


# If we ended up having download the Wiktionary dump, delete it once the build
# finishes since we've saved the data in a preprocessed format.
if [ -e data/enwiktionary-latest-pages-articles.xml ] ; then
    rm data/enwiktionary-latest-pages-articles.xml
fi

echo "Finished!"
