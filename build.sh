#!/bin/bash


# Check for grind
hash grind 2>/dev/null || { echo >&2 "WN Grind is required but it's not installed.  Aborting."; exit 1; }

if [ ! -d WordNet-3.0 ] ; then
    echo 'WordNet 3.0 not found in the local directory; downloading ...'
    wget http://wordnetcode.princeton.edu/3.0/WordNet-3.0.tar.gz
    tar xzf WordNet-3.0.tar.gz
    rm WordNet-3.0.tar.gz
    # For some reason WordNet does not come with write permissions on some of
    # its files, which interferes with us copying and then overwriting them.
    chmod u+w WordNet-3.0/dict/*
fi

if [ ! -d dbfiles-orig ] ; then
    echo 'Lexicographer files not found in the local directory; downloading ...'
    wget http://wordnetcode.princeton.edu/tools/grind/WNgrind-3.0.tar.gz
    tar xzf WNgrind-3.0.tar.gz
    mv WNgrind-3.0/dict/dbfiles dbfiles-orig
    rm WNgrind-3.0.tar.gz
    rm -rf WNgrind-3.0
fi

if [ ! -d working-files ] ; then
    mkdir working-files
fi

if [ ! -d classes ] ; then
    mkdir classes
fi

if [ ! -d crown-dbfiles ] ; then
    mkdir crown-dbfiles
fi

if [ ! -d crown-dict ] ; then
    mkdir crown-dict
fi




# Compile all the scripts
#
# This requires the lib/ directory to have:
# (1) Stanford CoreNLP
# (2) MIT JWI
# (3) S-Space Package
# (4) GNU Trove
javac -cp 'lib/*' -d classes/ src/*.java

# The input file that starts everything
wiktgloss_file=data/all.glosses.2.tsv

# The extracted Wikisaurus data, which isn't used until we start trying to
# attach lemmas to senses
wikisaurus_file=data/wikisaurus.relations.tsv

# The relations that were extracted directly from Wiktionary using its API.
# These are largely complementary to the Wikisaurus data but because both are
# constructed separately, they are not identical.
wiktionary_relations_file=data/wiktionary.relations.tsv

# The pipeline runs in two modes:
#
# "oov" attaches all OOV words (words not in WordNet) to WordNet synsets
# "wn-mono" attaches words that *are* in WordNet and are monosemous to sysets
#
# You probably want "oov".  The "wn-mono" mode is only intended for estimating
# the attachment precision and recall for attachment

#pipeline_mode="wn-mono"
pipeline_mode="oov"

# The log file where we report most of the files
if [ ! -d working-files/logs ] ; then
    mkdir working-files/logs
fi
crown_log_file=working-files/logs/crown-build.$pipeline_mode.log

#
# A version tag that will be appended to all the files in case you want to
# compare different versions
#
version=1.0

#
# The necessary directories
#
wordnet_dir=WordNet-3.0
# Should contain all the WNGrind lexicograph files
lexfile_dir=dbfiles-orig
# Should contain all the WNGrind lexicograph files, which we add to
new_lexfile_dir=crown-dbfiles
# Where the new WordNet dictionary is produced
new_wn_dict_dir=crown-dict
# Where all the intermediated input and output files are stored
working_files_dir=working-files

# Sanity checks to ensure all the files and directories exist
if [ ! -f "$wiktgloss_file" ]; then
    echo "Wiktionary gloss file does not exist: $wiktgloss_file"
    exit
fi
if [ ! -f "$wikisaurus_file" ]; then
    echo "The extracted Wikisaurs data from Wiktionary does not exist: $wiktgloss_file"
    exit
fi
if [ ! -f "$wiktionary_relations_file" ]; then
    echo "The extracted Wiktionary relations data does not exist: $wiktionary_relations_file"
    exit
fi


if [ ! -d "$wordnet_dir" ]; then
    echo "Specified WordNet directory does not exist: $wordnet_dir"
    exit 1
fi
if [ ! -d "$lexfile_dir" ]; then
    echo "Specified directory with lexicographer files does not exist: $lexfile_dir"
    exit 1
fi
if [ ! -d "$new_lexfile_dir" ]; then
    echo "Specified directory for the new lexicographfiles does not exist: $new_lexfile_dir"
    exit 1
fi
if [ ! -d "$new_wn_dict_dir" ]; then
    echo "Specified directory for the new WordNet dict/ does not exist: $new_wn_dict_dir"
    exit 1
fi
if [ ! -d "$working_files_dir" ]; then
    echo "Directory for the construction process's working files does not exist: $working_files_dir"
    exit 1
fi


# Intermediate and output files that we generate
preprocessed_wikt_gloss_file=$working_files_dir/wiktionary-sense-glosses.$pipeline_mode.$version.tsv

lexicalizations_file=$working_files_dir/lexicalizations.$pipeline_mode.$version.tsv
near_synonyms_file=$working_files_dir/near_synonyms.$pipeline_mode.$version.tsv
synonyms_file=$working_files_dir/synonyms.$pipeline_mode.$version.tsv
special_case_senses_file=$working_files_dir/special_case_senses_file.$pipeline_mode.$version.tsv
unprocessed_glosses_file=$working_files_dir/unprocessed-glosses.$pipeline_mode.$version.tsv


hypernym_candidates_file=$working_files_dir/lemma-to-hypernym-candidates.$pipeline_mode.$version.tsv
wikt_sense_to_gloss_file=$working_files_dir/wikt-sense-to-gloss.$pipeline_mode.$version.tsv
lemma_gloss_weights_file=$working_files_dir/lemma-to-gloss-prob.$pipeline_mode.$version.tsv
oov_lemma_to_hypernym_file=$working_files_dir/lemma-to-hypernym.$pipeline_mode.$version.tsv
synset_lexfile_mapping_file=$working_files_dir/synset-id-to-lexfile-id.$pipeline_mode.$version.tsv
antonym_file=$working_files_dir/sense-to-antonym.$pipeline_mode.$version.tsv

# These are just subsets of the preprocessed_wikt_gloss_file split out for
# convenience
preprocessed_wikt_subdef_file=$working_files_dir/wiktionary-sense-glosses.subdef-only.$pipeline_mode.$version.tsv
preprocessed_wikt_lexicalization_file=$working_files_dir/wiktionary-sense-glosses.lexicalization-only.$pipeline_mode.$version.tsv

# These files aren't strictly necessary, but the files helps keep track of which
# items couldn't be added to the lexicographer file for one reason or another
lexfile_generation_error_log=$working_files_dir/lexfile.err.$pipeline_mode.$version.log
grind_log=grind.wikt.log

######
# 
# CROWN generation follows below
#
######

# Process the original Wiktionary glosses to generate (1) the list of
# subdefintions for each gloss, which are separted by ';', (2) cleaned-up
# version of the gloss that remove Wiki markups, (3) the list of wikified
# terms and MWEs, and (4) the unique lexicalizations found in the file
echo "Preprocessing Wiktionary glosses"
#java -cp 'lib/*:classes/' PreprocessGlosses \
#     $wiktgloss_file \
#     2>/dev/null \
#     > $preprocessed_wikt_gloss_file

# Split preprocessed_wikt_gloss_file into two files
#grep SUBDEF $preprocessed_wikt_gloss_file \
#     > $preprocessed_wikt_subdef_file
#grep LEMMATIZED $preprocessed_wikt_gloss_file \
#    > $preprocessed_wikt_lexicalization_file


# Decide where we could to attach each OOV lemma.
echo "Generating hypernym candidates for OOV lemmas"
#java -cp 'lib/*:classes/' GenerateHypernymCandidates \
#     $preprocessed_wikt_subdef_file \
#     $wordnet_dir/dict \
#     $pipeline_mode \
#     2>/dev/null \
#     > $hypernym_candidates_file 

     
# Extract the full cleaned-up glosses per Wiktionary from dewikified
# subdefitions for each sense.  We need these cleaned-up glosses for string
# comparison later when tie-breaking
echo "Creating cleaned Wiktionary sense glosses"
#java -cp 'lib/*:classes/' MakeSenseGlosses \
#     $preprocessed_wikt_subdef_file \
#     > $wikt_sense_to_gloss_file

# Compute the relative frequency with which terms are mentioned in the glosses.
# We need this to weight the relative importances of shared terminology between
# glosses
echo "Computing lemma gloss-overlap weights"
#java -cp 'lib/*:classes/' ComputeInvLemmaGlossFreq \
#     $wikt_sense_to_gloss_file \
#     2>/dev/null \
#     > $lemma_gloss_weights_file 


# Extract out the possible antonyms in the dataset.  These antonyms will assist
# in attaching the synsets where possible since we know their antonym in WordNet
# (if it exists) should be a coordinate term
echo "Extracting antonyms"
#java -Xmx3g  -cp 'lib/*:classes/' ExtractAntonyms \
#     $hypernym_candidates_file \
#     $wordnet_dir/dict/ \
#     > $antonym_file

# Once we have the lemma weighting and full glosses ready, evaluate the list of
# candidate hypernym attachments for each lemma and report the best one
echo "Attaching OOV lemmas to WordNet hypernyms"
#java -Xmx4g -Dcrown.logfile=$crown_log_file -cp 'lib/*:classes/' AttachLemmaToHypernym \
#     $hypernym_candidates_file \
#     $wordnet_dir/dict/ \
#     $lemma_gloss_weights_file \
#     $wikt_sense_to_gloss_file \
#     $antonym_file \
#     $wikisaurus_file \
#     $wiktionary_relations_file \
#     2>$working_files_dir/attach.debug.$pipeline_mode.$version.log \
#     > $oov_lemma_to_hypernym_file

# If we're running in "wn-mono" mode, there's no reason to generate
# lexicographer files (the synset-ids produced above are sufficient for
# scoring), so stop early
if [ "$pipeline_mode" = "wn-mono" ]; then
    echo "Done generating attachments for monosemous lemmas in WordNet."
    exit
fi

# Clean up the files in the CROWN lexfile directory if there was content from an
# old build
chmod u+w $new_lexfile_dir//*
rm $new_lexfile_dir/*


# Copy over the old lexfiles to the new lexfile directory
cp $lexfile_dir/* $new_lexfile_dir/

# Before we can attach to each synset, we need to figure out how that synset is
# represented in the lexicographer files.  This means generating a mapping from
# synset-id to a name that can be used in the lexicographer files by grind
echo "Creating synset-lexfile identifier mapping"
java -cp 'lib/*:classes/' MapIdsToLexIds \
     $lexfile_dir \
     $wordnet_dir/dict \
     > $synset_lexfile_mapping_file



# After each OOV lemma is assigned to a particular hypernym, generate the
# lexicographer markup file for use with WN's grind program.  Because WN grind
# needs the data POS-specific files, we run this four times, one for each POS
echo "Creating new lexicographer files for OOV words"
echo -n "" > $lexfile_generation_error_log


java -cp 'lib/*:classes/' CreateLexFile \
     <( grep '\.n\.' $oov_lemma_to_hypernym_file ) \
     $synset_lexfile_mapping_file \
     $wikt_sense_to_gloss_file \
     $wordnet_dir/dict/ \
     noun \
     > $new_lexfile_dir/noun.wikt \
     2>> $lexfile_generation_error_log

java -cp 'lib/*:classes/' CreateLexFile \
     <( grep '\.v\.' $oov_lemma_to_hypernym_file ) \
     $synset_lexfile_mapping_file \
     $wikt_sense_to_gloss_file \
     $wordnet_dir/dict/ \
     verb \
     > $new_lexfile_dir/verb.wikt \
     2>> $lexfile_generation_error_log

java -cp 'lib/*:classes/' CreateLexFile \
     <( grep '\.a\.' $oov_lemma_to_hypernym_file ) \
     $synset_lexfile_mapping_file \
     $wikt_sense_to_gloss_file \
     $wordnet_dir/dict/ \
     adj \
     | grep -v unblooded > $new_lexfile_dir/adj.wikt \
     2>> $lexfile_generation_error_log

java -cp 'lib/*:classes/' CreateLexFile \
     <( grep '\.r\.' $oov_lemma_to_hypernym_file ) \
    $synset_lexfile_mapping_file \
     $wikt_sense_to_gloss_file \
     $wordnet_dir/dict/ \
     adv \
     > $new_lexfile_dir/adv.wikt \
     2>> $lexfile_generation_error_log


# Clean up the files in the new dictionary directory if there was content from
# an old build
chmod u+w $new_wn_dict_dir/*
rm $new_wn_dict_dir/*

# Run WN grind on the lexfiles.
echo "Running grind to produce new WordNet DB files"
pushd $new_lexfile_dir
# NOTE: grind is going to complain a lot because none of the new verbs have
# associated frames yet.  Therefore, we report all *but* this output just to
# save the viewer from ~10K of useless info
grind  -a  -i -o cntlist -n -L$grind_log noun.* verb.* adj.* adv.* \
    | grep -v 'No frame list in verb synset'
cp data.* ../$new_wn_dict_dir
cp index.* ../$new_wn_dict_dir
popd

# In order to correctly generate the morphological exception list, we first
# create a half-working copy of the CROWN with WordNet's existing exception list.
# We need the original list in order for CROWN to work.  The reason we need CROWN
# instead of just using WordNet directly is to generate morphological variants
# of the OOV data s well, since the CreateExc class won't generate exceptions
# for lemmas not in the dictionary.  These dictionaries will be overwritten
# later after we generate the CROWN .exc files
# 
cp $wordnet_dir/dict/noun.exc $new_wn_dict_dir/noun.exc
cp $wordnet_dir/dict/verb.exc $new_wn_dict_dir/verb.exc
cp $wordnet_dir/dict/adj.exc $new_wn_dict_dir/adj.exc
cp $wordnet_dir/dict/adv.exc $new_wn_dict_dir/adv.exc
cp $wordnet_dir/dict/sents.vrb $new_wn_dict_dir/sents.vrb
cp $wordnet_dir/dict/sentidx.vrb $new_wn_dict_dir/sentidx.vrb

chmod u+w $new_wn_dict_dir/*.exc

# Generate the .exc files for WordNet from the multiple lexicalizations reported
# in Wiktionary.  These are POS-specific, so we run the program four times to
# generate one for each
echo "Generating lexicalization exceptions"
java -cp 'lib/*:classes/' CreateExc \
     <( grep '\.r\.' $preprocessed_wikt_lexicalization_file ) \
     $new_wn_dict_dir/ \
     > $working_files_dir/adv.wikt.exc
java -cp 'lib/*:classes/' CreateExc \
     <( grep '\.a\.' $preprocessed_wikt_lexicalization_file ) \
     $new_wn_dict_dir/ \
     > $working_files_dir/adj.wikt.exc
java -cp 'lib/*:classes/' CreateExc \
     <( grep '\.v\.' $preprocessed_wikt_lexicalization_file ) \
     $new_wn_dict_dir/ \
     > $working_files_dir/verb.wikt.exc
java -cp 'lib/*:classes/' CreateExc \
     <( grep '\.n\.' $preprocessed_wikt_lexicalization_file ) \
     $new_wn_dict_dir/ \
     > $working_files_dir/noun.wikt.exc


# Combined the existing and new lexicalization exception.  Note that WordNet
# requires the .exc files to be in sorted order (otherwise they won't work at
# all), so the pipe to sort is essential here for the morphological exceptions
# to be correctly recognized.
cat $wordnet_dir/dict/adv.exc $working_files_dir/adv.wikt.exc \
    | sort -u \
          > $new_wn_dict_dir/adv.exc
cat $wordnet_dir/dict/adj.exc $working_files_dir/adj.wikt.exc \
    | sort -u \
          > $new_wn_dict_dir/adj.exc
cat $wordnet_dir/dict/verb.exc $working_files_dir/verb.wikt.exc \
    | sort -u \
          > $new_wn_dict_dir/verb.exc
cat $wordnet_dir/dict/noun.exc $working_files_dir/noun.wikt.exc \
    | sort -u \
          > $new_wn_dict_dir/noun.exc

echo "Finished!"
